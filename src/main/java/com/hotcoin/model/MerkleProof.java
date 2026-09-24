package com.hotcoin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一条真实叶子的完整证明。path 自叶子向根排列，仅包含每层兄弟节点，不附加根节点。
 * 所有非空容器均防御复制；允许保留非法或空字段，由验证器统一返回 false。
 */
public final class MerkleProof {
    private final String protocol;
    private final List<Asset> assets;
    private final Self self;
    private final List<Step> path;

    @JsonCreator
    public MerkleProof(@JsonProperty("protocol") String protocol,
                       @JsonProperty("assets") List<Asset> assets,
                       @JsonProperty("self") Self self,
                       @JsonProperty("path") List<Step> path) {
        this.protocol = protocol;
        this.assets = ModelCopies.list(assets);
        this.self = self;
        this.path = ModelCopies.list(path);
    }

    public String protocol() {
        return protocol;
    }

    public String getProtocol() {
        return protocol;
    }

    public List<Asset> assets() {
        return assets;
    }

    public List<Asset> getAssets() {
        return assets;
    }

    public Self self() {
        return self;
    }

    public Self getSelf() {
        return self;
    }

    public List<Step> path() {
        return path;
    }

    public List<Step> getPath() {
        return path;
    }

    /** 待验证叶子的信息；leafIndex 从 0 开始，leafCount 只统计真实叶子。 */
    public static final class Self {
        private final String snapshotId;
        private final String accountId;
        private final String nonce;
        private final Map<String, String> balances;
        private final String leafHash;
        private final int leafIndex;
        private final int leafCount;

        @JsonCreator
        public Self(@JsonProperty("snapshotId") String snapshotId,
                    @JsonProperty("accountId") String accountId,
                    @JsonProperty("nonce") String nonce,
                    @JsonProperty("balances") Map<String, String> balances,
                    @JsonProperty("leafHash") String leafHash,
                    @JsonProperty("leafIndex") int leafIndex,
                    @JsonProperty("leafCount") int leafCount) {
            this.snapshotId = snapshotId;
            this.accountId = accountId;
            this.nonce = nonce;
            this.balances = ModelCopies.map(balances);
            this.leafHash = leafHash;
            this.leafIndex = leafIndex;
            this.leafCount = leafCount;
        }

        public String snapshotId() {
            return snapshotId;
        }

        public String getSnapshotId() {
            return snapshotId;
        }

        public String accountId() {
            return accountId;
        }

        public String getAccountId() {
            return accountId;
        }

        public String nonce() {
            return nonce;
        }

        public String getNonce() {
            return nonce;
        }

        public Map<String, String> balances() {
            return balances;
        }

        public Map<String, String> getBalances() {
            return balances;
        }

        public String leafHash() {
            return leafHash;
        }

        public String getLeafHash() {
            return leafHash;
        }

        public int leafIndex() {
            return leafIndex;
        }

        public int getLeafIndex() {
            return leafIndex;
        }

        public int leafCount() {
            return leafCount;
        }

        public int getLeafCount() {
            return leafCount;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Self)) {
                return false;
            }
            Self that = (Self) other;
            return leafIndex == that.leafIndex && leafCount == that.leafCount
                    && Objects.equals(snapshotId, that.snapshotId)
                    && Objects.equals(accountId, that.accountId)
                    && Objects.equals(nonce, that.nonce)
                    && Objects.equals(balances, that.balances)
                    && Objects.equals(leafHash, that.leafHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, accountId, nonce, balances, leafHash, leafIndex, leafCount);
        }
    }

    /** 一层证明的兄弟节点；position 表示兄弟位于当前节点的左侧还是右侧。 */
    public static final class Step {
        private final Position position;
        private final String hash;
        private final Map<String, String> balances;

        @JsonCreator
        public Step(@JsonProperty("position") Position position,
                    @JsonProperty("hash") String hash,
                    @JsonProperty("balances") Map<String, String> balances) {
            this.position = position;
            this.hash = hash;
            this.balances = ModelCopies.map(balances);
        }

        public Position position() {
            return position;
        }

        public Position getPosition() {
            return position;
        }

        public String hash() {
            return hash;
        }

        public String getHash() {
            return hash;
        }

        public Map<String, String> balances() {
            return balances;
        }

        public Map<String, String> getBalances() {
            return balances;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Step)) {
                return false;
            }
            Step that = (Step) other;
            return position == that.position && Objects.equals(hash, that.hash)
                    && Objects.equals(balances, that.balances);
        }

        @Override
        public int hashCode() {
            return Objects.hash(position, hash, balances);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MerkleProof)) {
            return false;
        }
        MerkleProof that = (MerkleProof) other;
        return Objects.equals(protocol, that.protocol) && Objects.equals(assets, that.assets)
                && Objects.equals(self, that.self) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, assets, self, path);
    }

    /** 兄弟节点的方向，不表示当前节点的方向。 */
    public enum Position {
        LEFT, RIGHT
    }
}
