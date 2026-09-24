package com.hotcoin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从独立公布渠道取得的可信批次信息。
 * userTotals 是入树用户余额合计，不是链上资产；不能从待验 proof 自造可信根。
 */
public final class PublicAnchor {
    private final String protocol;
    private final List<Asset> assets;
    private final String snapshotId;
    private final String root;
    private final int leafCount;
    private final Map<String, String> userTotals;

    @JsonCreator
    public PublicAnchor(@JsonProperty("protocol") String protocol,
                        @JsonProperty("assets") List<Asset> assets,
                        @JsonProperty("snapshotId") String snapshotId,
                        @JsonProperty("root") String root,
                        @JsonProperty("leafCount") int leafCount,
                        @JsonProperty("userTotals") Map<String, String> userTotals) {
        this.protocol = protocol;
        this.assets = ModelCopies.list(assets);
        this.snapshotId = snapshotId;
        this.root = root;
        this.leafCount = leafCount;
        this.userTotals = ModelCopies.map(userTotals);
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

    public String snapshotId() {
        return snapshotId;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public String root() {
        return root;
    }

    public String getRoot() {
        return root;
    }

    public int leafCount() {
        return leafCount;
    }

    public int getLeafCount() {
        return leafCount;
    }

    public Map<String, String> userTotals() {
        return userTotals;
    }

    public Map<String, String> getUserTotals() {
        return userTotals;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicAnchor)) {
            return false;
        }
        PublicAnchor that = (PublicAnchor) other;
        return leafCount == that.leafCount
                && Objects.equals(protocol, that.protocol)
                && Objects.equals(assets, that.assets)
                && Objects.equals(snapshotId, that.snapshotId)
                && Objects.equals(root, that.root)
                && Objects.equals(userTotals, that.userTotals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, assets, snapshotId, root, leafCount, userTotals);
    }

    @Override
    public String toString() {
        return "PublicAnchor{" + "protocol='" + protocol + '\'' + ", assets=" + assets
                + ", snapshotId='" + snapshotId + '\'' + ", root='" + root + '\''
                + ", leafCount=" + leafCount + ", userTotals=" + userTotals + '}';
    }
}
