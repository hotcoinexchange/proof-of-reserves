package com.hotcoin.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotcoin.model.Asset;
import com.hotcoin.model.MerkleProof;
import com.hotcoin.model.PublicAnchor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 验证 JSON 输入边界：严格检查个人证明和可信根信息的字段与原始类型。 */
final class MerkleJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private MerkleJson() {
    }

    static MerkleProof proof(String json) {
        JsonNode root = object(read(json), "protocol", "assets", "self", "path");
        JsonNode self = object(root.get("self"), "snapshotId", "accountId", "nonce", "balances",
                "leafHash", "leafIndex", "leafCount");
        MerkleProof.Self own = new MerkleProof.Self(text(self.get("snapshotId")),
                text(self.get("accountId")), text(self.get("nonce")), amounts(self.get("balances")),
                text(self.get("leafHash")), integer(self.get("leafIndex")), integer(self.get("leafCount")));
        List<MerkleProof.Step> path = new ArrayList<>();
        for (JsonNode item : array(root.get("path"))) {
            JsonNode step = object(item, "position", "hash", "balances");
            MerkleProof.Position position = switch (text(step.get("position"))) {
                case "LEFT" -> MerkleProof.Position.LEFT;
                case "RIGHT" -> MerkleProof.Position.RIGHT;
                default -> throw invalid("Invalid proof position");
            };
            path.add(new MerkleProof.Step(position, text(step.get("hash")), amounts(step.get("balances"))));
        }
        return new MerkleProof(text(root.get("protocol")), assets(root.get("assets")), own, path);
    }

    static PublicAnchor anchor(String json) {
        JsonNode root = object(read(json), "protocol", "assets", "snapshotId", "root", "leafCount", "userTotals");
        return new PublicAnchor(text(root.get("protocol")), assets(root.get("assets")),
                text(root.get("snapshotId")), text(root.get("root")), integer(root.get("leafCount")),
                amounts(root.get("userTotals")));
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            throw invalid("JSON input is required");
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException failure) {
            throw invalid("Invalid Merkle JSON");
        }
    }

    private static JsonNode object(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            throw invalid("Expected JSON object");
        }
        Set<String> expected = Set.of(fields);
        if (node.size() != expected.size()) {
            throw invalid("Missing or unknown JSON fields");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!expected.contains(names.next())) {
                throw invalid("Unknown JSON field");
            }
        }
        for (String field : fields) {
            if (!node.hasNonNull(field)) {
                throw invalid("Required JSON field is missing or null");
            }
        }
        return node;
    }

    private static JsonNode array(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw invalid("Expected JSON array");
        }
        return node;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw invalid("Expected JSON string");
        }
        return node.textValue();
    }

    private static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid("Expected JSON integer in int range");
        }
        return node.intValue();
    }

    private static List<Asset> assets(JsonNode node) {
        List<Asset> result = new ArrayList<>();
        for (JsonNode item : array(node)) {
            JsonNode asset = object(item, "coin", "decimals");
            result.add(new Asset(text(asset.get("coin")), integer(asset.get("decimals"))));
        }
        return result;
    }

    private static Map<String, String> amounts(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("Expected balance object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            result.put(field.getKey(), text(field.getValue()));
        }
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
