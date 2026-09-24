package com.hotcoin.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按 leafIndex 顺序导出的真实叶子，用于业务侧持久化。 */
public final class MerkleLeafRecord {
    private final int leafIndex;
    private final String accountId;
    private final String nonce;
    private final String leafHash;
    private final Map<String, String> balances;

    public MerkleLeafRecord(int leafIndex, String accountId, String nonce,
                            String leafHash, Map<String, String> balances) {
        this.leafIndex = leafIndex;
        this.accountId = accountId;
        this.nonce = nonce;
        this.leafHash = leafHash;
        this.balances = balances == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(balances));
    }

    public int getLeafIndex() {
        return leafIndex;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getNonce() {
        return nonce;
    }

    public String getLeafHash() {
        return leafHash;
    }

    public Map<String, String> getBalances() {
        return balances;
    }
}
