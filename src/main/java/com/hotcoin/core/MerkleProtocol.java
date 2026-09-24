package com.hotcoin.core;

import com.hotcoin.model.Asset;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 包内协议实现，统一建树及验证使用的金额规范、节点编码和哈希规则。 */
final class MerkleProtocol {
    static final String PROTOCOL = "HOTCOIN-POR-SUM";
    static final int MAX_USER_AMOUNT_CHARS = 100;
    // 真实叶子数量使用 int，最多累加 Integer.MAX_VALUE 项，预留 10 位整数增长。
    static final int MAX_SUM_AMOUNT_CHARS = 110;

    private static final Pattern COIN = Pattern.compile("[A-Z][A-Z0-9]{0,15}");
    private static final Pattern SNAPSHOT_ID = Pattern.compile(
            "(?:[A-Za-z0-9_-]{1,64}|\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    });

    private MerkleProtocol() {
    }

    /** 内部不可变节点，真实零额叶与虚拟补零节点使用不同域的哈希。 */
    static final class Node {
        private final String hash;
        private final Map<String, String> balances;

        Node(String hash, Map<String, String> balances) {
            this.hash = hash;
            this.balances = Collections.unmodifiableMap(new LinkedHashMap<>(balances));
        }

        String hash() {
            return hash;
        }

        Map<String, String> balances() {
            return balances;
        }
    }

    static List<Asset> checkedAssets(List<Asset> assets) {
        require(assets != null && !assets.isEmpty() && assets.size() <= 32, "Asset count must be 1..32");
        Set<String> coins = new LinkedHashSet<>();
        for (Asset asset : assets) {
            require(asset != null && asset.coin() != null && COIN.matcher(asset.coin()).matches(), "Invalid coin");
            require(asset.decimals() >= 0 && asset.decimals() <= 30, "Precision must be 0..30");
            require(coins.add(asset.coin()), "Duplicate coin");
        }
        return List.copyOf(assets);
    }

    static Map<String, String> normalize(List<Asset> assets, Map<String, String> input, int maxChars) {
        require(input != null, "Balances are required");
        // assets 已完成唯一性校验，数量相同且逐币取到合法值即可确认键集合一致。
        require(input.size() == assets.size(), "Balance keys must exactly match assets");
        Map<String, String> result = new LinkedHashMap<>();
        for (Asset asset : assets) {
            String raw = input.get(asset.coin());
            require(raw != null && !raw.isEmpty() && raw.length() <= maxChars
                    && DECIMAL.matcher(raw).matches(), "Invalid amount");
            BigDecimal amount = new BigDecimal(raw);
            // 必须在去尾零前校验原始精度，不能通过多填零绕过账本位数限制。
            require(amount.scale() <= asset.decimals(), "Amount exceeds ledger precision");
            String canonical = amount.stripTrailingZeros().toPlainString();
            require(canonical.length() <= maxChars, "Canonical amount is too long");
            result.put(asset.coin(), canonical);
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, String> canonical(List<Asset> assets, Map<String, String> input, int maxChars) {
        Map<String, String> normalized = normalize(assets, input, maxChars);
        require(normalized.equals(input), "Proof amounts must be canonical");
        return normalized;
    }

    static Map<String, String> add(List<Asset> assets, Map<String, String> left, Map<String, String> right) {
        Map<String, String> sum = new LinkedHashMap<>();
        for (Asset asset : assets) {
            String value = new BigDecimal(left.get(asset.coin())).add(new BigDecimal(right.get(asset.coin())))
                    .stripTrailingZeros().toPlainString();
            require(value.length() <= MAX_SUM_AMOUNT_CHARS, "Aggregate amount is too long");
            sum.put(asset.coin(), value);
        }
        return Collections.unmodifiableMap(sum);
    }

    static Map<String, String> zeroAmounts(List<Asset> assets) {
        Map<String, String> zeros = new LinkedHashMap<>();
        for (Asset asset : assets) {
            zeros.put(asset.coin(), "0");
        }
        return Collections.unmodifiableMap(zeros);
    }

    static String configurationHash(List<Asset> assets) {
        List<String> fields = new ArrayList<>(List.of(PROTOCOL));
        for (Asset asset : assets) {
            fields.add(asset.coin());
            fields.add(Integer.toString(asset.decimals()));
        }
        return hash(3, fields);
    }

    static Node leafNode(String snapshotId, List<Asset> assets, String configHash,
                         String accountId, String nonce, Map<String, String> balances) {
        List<String> fields = new ArrayList<>(List.of(PROTOCOL, snapshotId, configHash, accountId, nonce));
        appendAmounts(fields, assets, balances);
        return new Node(hash(0, fields), balances);
    }

    static Node paddingNode(String snapshotId, List<Asset> assets, String configHash) {
        return new Node(hash(2, List.of(PROTOCOL, snapshotId, configHash)), zeroAmounts(assets));
    }

    static Node parent(List<Asset> assets, String configHash, Node left, Node right) {
        Map<String, String> sum = add(assets, left.balances(), right.balances());
        List<String> fields = new ArrayList<>(List.of(PROTOCOL, configHash, left.hash(), right.hash()));
        appendAmounts(fields, assets, sum);
        return new Node(hash(1, fields), sum);
    }

    private static void appendAmounts(List<String> fields, List<Asset> assets, Map<String, String> balances) {
        for (Asset asset : assets) {
            fields.add(balances.get(asset.coin()));
        }
    }

    private static String hash(int domain, List<String> fields) {
        MessageDigest sha256 = DIGEST.get();
        sha256.reset();
        sha256.update((byte) domain);
        sha256.update(jsonStringArray(fields).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha256.digest());
    }

    /** 固定使用紧凑 JSON 字符串数组编码，不依赖 Map 的迭代顺序或外部 JSON 库。 */
    private static String jsonStringArray(List<String> fields) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            String value = Objects.requireNonNull(fields.get(i));
            json.append('"');
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                switch (c) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (Character.isHighSurrogate(c) && j + 1 < value.length()
                                && Character.isLowSurrogate(value.charAt(j + 1))) {
                            json.append(c).append(value.charAt(++j));
                        } else if (c < 0x20 || Character.isSurrogate(c)) {
                            json.append("\\u").append(HEX[(c >>> 12) & 15]).append(HEX[(c >>> 8) & 15])
                                    .append(HEX[(c >>> 4) & 15]).append(HEX[c & 15]);
                        } else {
                            json.append(c);
                        }
                    }
                }
            }
            json.append('"');
        }
        return json.append(']').toString();
    }

    static boolean isHash(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    static void requireSnapshotId(String value) {
        require(value != null && SNAPSHOT_ID.matcher(value).matches(), "Invalid snapshot ID");
    }

    static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
