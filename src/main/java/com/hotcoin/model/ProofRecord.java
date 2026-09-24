package com.hotcoin.model;

import java.util.Objects;

/** 一名用户的完整证明；leafIndex 用于调用方关联业务用户，proofJson 可直接保存。 */
public final class ProofRecord {
    private final int leafIndex;
    private final String proofJson;

    public ProofRecord(int leafIndex, String proofJson) {
        this.leafIndex = leafIndex;
        this.proofJson = proofJson;
    }

    public int leafIndex() {
        return leafIndex;
    }

    public int getLeafIndex() {
        return leafIndex;
    }

    public String proofJson() {
        return proofJson;
    }

    public String getProofJson() {
        return proofJson;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProofRecord)) {
            return false;
        }
        ProofRecord that = (ProofRecord) other;
        return leafIndex == that.leafIndex && Objects.equals(proofJson, that.proofJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leafIndex, proofJson);
    }

    @Override
    public String toString() {
        return "ProofRecord{" + "leafIndex=" + leafIndex + ", proofJson='" + proofJson + '\'' + '}';
    }
}
