package com.hotcoin.core;

import com.hotcoin.model.Asset;
import com.hotcoin.model.LeafInput;
import com.hotcoin.model.PublicAnchor;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 批次计算上下文；金额在内部使用最小单位整数，哈希编码保持原协议。 */
final class MerkleComputation {
    private final String snapshotId;
    private final List<Asset> assets;
    private final BigInteger[] powersOfTen;
    private final byte[] leafPrefix;
    private final byte[] parentPrefix;
    private final Node padding;

    /** 包内节点所有权：数组交给节点后只读，不可继续用于累加或复用。 */
    static final class Node {
        private final String hash;
        private final BigInteger[] amounts;

        Node(String hash, BigInteger[] amounts) {
            this.hash = hash;
            this.amounts = amounts;
        }

        String hash() {
            return hash;
        }

        BigInteger[] amounts() {
            return amounts;
        }
    }

    MerkleComputation(String snapshotId, List<Asset> assets) {
        MerkleProtocol.requireSnapshotId(snapshotId);
        this.snapshotId = snapshotId;
        this.assets = MerkleProtocol.checkedAssets(assets);
        int maxDecimals = 0;
        for (Asset asset : this.assets) {
            maxDecimals = Math.max(maxDecimals, asset.decimals());
        }
        powersOfTen = new BigInteger[maxDecimals + 1];
        powersOfTen[0] = BigInteger.ONE;
        for (int i = 1; i < powersOfTen.length; i++) {
            powersOfTen[i] = powersOfTen[i - 1].multiply(BigInteger.TEN);
        }
        String configurationHash = MerkleProtocol.configurationHash(this.assets);
        leafPrefix = prefix(0, MerkleProtocol.PROTOCOL, snapshotId, configurationHash);
        parentPrefix = prefix(1, MerkleProtocol.PROTOCOL, configurationHash);
        Worker worker = worker();
        worker.start(prefix(2, MerkleProtocol.PROTOCOL, snapshotId, configurationHash));
        padding = new Node(worker.finish(), zeroAmounts());
    }

    List<Asset> assets() {
        return assets;
    }

    String snapshotId() {
        return snapshotId;
    }

    Node padding() {
        return padding;
    }

    Worker worker() {
        return new Worker();
    }

    BigInteger[] zeroAmounts() {
        BigInteger[] values = new BigInteger[assets.size()];
        Arrays.fill(values, BigInteger.ZERO);
        return values;
    }

    Map<String, String> amounts(BigInteger[] values) {
        requireVector(values);
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < assets.size(); i++) {
            result.put(assets.get(i).coin(), amount(values[i], i));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 导出单币金额，避免每个证明路径节点先构造临时 Map。 */
    String amount(BigInteger value, int assetIndex) {
        MerkleProtocol.require(value != null && value.signum() >= 0, "Invalid amount");
        String canonical = canonical(value, assets.get(assetIndex).decimals());
        MerkleProtocol.require(canonical.length() <= MerkleProtocol.MAX_SUM_AMOUNT_CHARS,
                "Aggregate amount is too long");
        return canonical;
    }

    /** 独立累计输入总额，仅修改调用方的 total，不修改已有节点。 */
    void accumulate(BigInteger[] total, BigInteger[] values) {
        requireVector(total);
        requireVector(values);
        for (int i = 0; i < total.length; i++) {
            BigInteger sum = total[i].add(values[i]);
            // 精度不超过 30；256 位整数还原十进制最多 79 字符，可安全跳过格式化。
            if (sum.bitLength() > 256) {
                MerkleProtocol.require(canonical(sum, assets.get(i).decimals()).length()
                                <= MerkleProtocol.MAX_SUM_AMOUNT_CHARS,
                        "Aggregate amount is too long");
            }
            total[i] = sum;
        }
    }

    PublicAnchor anchor(Node root, int leafCount) {
        MerkleProtocol.require(root != null && leafCount > 0, "Root and positive leaf count are required");
        return new PublicAnchor(MerkleProtocol.PROTOCOL, assets, snapshotId,
                root.hash(), leafCount, amounts(root.amounts()));
    }

    /** 每个工作线程独享摘要和编码缓冲，不得并发共享同一个 Worker。 */
    final class Worker {
        private final MessageDigest digest = newDigest();
        private byte[] buffer = new byte[512];
        private int length;

        Node leaf(LeafInput input) {
            MerkleProtocol.require(input != null, "Leaf is required");
            MerkleProtocol.require(MerkleProtocol.isHash(input.accountId()), "Invalid account ID");
            MerkleProtocol.require(MerkleProtocol.isHash(input.nonce()), "Invalid nonce");
            BigInteger[] values = parseAmounts(input.balances());
            start(leafPrefix);
            field(input.accountId());
            field(input.nonce());
            for (int i = 0; i < values.length; i++) {
                String value = canonical(values[i], assets.get(i).decimals());
                MerkleProtocol.require(value.length() <= MerkleProtocol.MAX_USER_AMOUNT_CHARS,
                        "Canonical amount is too long");
                field(value);
            }
            return new Node(finish(), values);
        }

        Node parent(Node left, Node right) {
            BigInteger[] values = new BigInteger[assets.size()];
            start(parentPrefix);
            field(left.hash());
            field(right.hash());
            for (int i = 0; i < values.length; i++) {
                values[i] = left.amounts()[i].add(right.amounts()[i]);
                String value = canonical(values[i], assets.get(i).decimals());
                MerkleProtocol.require(value.length() <= MerkleProtocol.MAX_SUM_AMOUNT_CHARS,
                        "Aggregate amount is too long");
                field(value);
            }
            return new Node(finish(), values);
        }

        private void start(byte[] prefix) {
            ensure(prefix.length);
            System.arraycopy(prefix, 0, buffer, 0, prefix.length);
            length = prefix.length;
        }

        /** 所有字段已限定为 ASCII 且不含引号或反斜杠，直接写原紧凑 JSON 字节。 */
        private void field(String value) {
            ensure(length + value.length() + 3);
            buffer[length++] = '"';
            for (int i = 0; i < value.length(); i++) {
                buffer[length++] = (byte) value.charAt(i);
            }
            buffer[length++] = '"';
            buffer[length++] = ',';
        }

        private String finish() {
            buffer[length - 1] = ']';
            digest.reset();
            digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        }

        private void ensure(int required) {
            if (required > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(required, buffer.length * 2));
            }
        }
    }

    private BigInteger[] parseAmounts(Map<String, String> input) {
        MerkleProtocol.require(input != null, "Balances are required");
        MerkleProtocol.require(input.size() == assets.size(), "Balance keys must exactly match assets");
        BigInteger[] values = new BigInteger[assets.size()];
        for (int i = 0; i < assets.size(); i++) {
            Asset asset = assets.get(i);
            String raw = input.get(asset.coin());
            MerkleProtocol.require(raw != null && !raw.isEmpty()
                    && raw.length() <= MerkleProtocol.MAX_USER_AMOUNT_CHARS, "Invalid amount");
            int point = -1;
            boolean nonZero = false;
            for (int j = 0; j < raw.length(); j++) {
                char character = raw.charAt(j);
                if (character == '.' && point < 0 && j > 0 && j < raw.length() - 1) {
                    point = j;
                } else {
                    MerkleProtocol.require(character >= '0' && character <= '9', "Invalid amount");
                    nonZero |= character != '0';
                }
            }
            int scale = point < 0 ? 0 : raw.length() - point - 1;
            // 包含全零值在内，必须在去尾零前检查原始小数位。
            MerkleProtocol.require(scale <= asset.decimals(), "Amount exceeds ledger precision");
            if (!nonZero) {
                values[i] = BigInteger.ZERO;
            } else {
                String digits = point < 0 ? raw : raw.substring(0, point) + raw.substring(point + 1);
                BigInteger unscaled = new BigInteger(digits);
                int remainingScale = asset.decimals() - scale;
                values[i] = remainingScale == 0 ? unscaled : unscaled.multiply(powersOfTen[remainingScale]);
            }
        }
        return values;
    }

    private void requireVector(BigInteger[] values) {
        MerkleProtocol.require(values != null && values.length == assets.size(), "Invalid amount vector");
        for (BigInteger value : values) {
            MerkleProtocol.require(value != null && value.signum() >= 0, "Invalid amount");
        }
    }

    private static String canonical(BigInteger value, int decimals) {
        if (value.signum() == 0) {
            return "0";
        }
        String digits = value.toString();
        if (decimals == 0) {
            return digits;
        }
        int point = digits.length() - decimals;
        int end = digits.length();
        while (end > Math.max(point, 0) && digits.charAt(end - 1) == '0') {
            end--;
        }
        if (point > 0) {
            return end == point ? digits.substring(0, point)
                    : digits.substring(0, point) + "." + digits.substring(point, end);
        }
        return "0." + "0".repeat(-point) + digits.substring(0, end);
    }

    private static byte[] prefix(int domain, String... fields) {
        StringBuilder json = new StringBuilder("[");
        for (String field : fields) {
            json.append('"').append(field).append("\",");
        }
        byte[] encoded = json.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[encoded.length + 1];
        result[0] = (byte) domain;
        System.arraycopy(encoded, 0, result, 1, encoded.length);
        return result;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
