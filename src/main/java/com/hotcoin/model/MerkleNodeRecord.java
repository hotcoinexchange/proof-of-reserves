package com.hotcoin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按层、按 nodeIndex 顺序导出的节点；level=0 为叶子层。 */
public final class MerkleNodeRecord {
    private final int level;
    private final int nodeIndex;
    private final String nodeHash;
    private final Map<String, String> balances;

    public MerkleNodeRecord(int level, int nodeIndex, String nodeHash,
                            Map<String, String> balances) {
        this.level = level;
        this.nodeIndex = nodeIndex;
        this.nodeHash = nodeHash;
        this.balances = balances == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(balances));
    }

    public int getLevel() {
        return level;
    }

    public int getNodeIndex() {
        return nodeIndex;
    }

    public String getNodeHash() {
        return nodeHash;
    }

    public Map<String, String> getBalances() {
        return balances;
    }
}
