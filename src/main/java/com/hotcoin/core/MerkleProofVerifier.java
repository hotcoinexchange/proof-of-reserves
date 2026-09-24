package com.hotcoin.core;

import com.hotcoin.model.Asset;
import com.hotcoin.model.MerkleProof;
import com.hotcoin.model.MerkleProof.Position;
import com.hotcoin.model.MerkleProof.Self;
import com.hotcoin.model.MerkleProof.Step;
import com.hotcoin.model.PublicAnchor;

import java.util.List;
import java.util.Map;

/** 独立证明验证入口；只进行本地金额计算及哈希校验，不读文件或访问网络。 */
public final class MerkleProofVerifier {
    private MerkleProofVerifier() {
    }

    /** 创建与协议一致的虚拟补零兄弟节点，供从外部存储重建证明路径。 */
    public static Step paddingStep(String snapshotId, List<Asset> assets, Position position) {
        try {
            List<Asset> checkedAssets = MerkleProtocol.checkedAssets(assets);
            MerkleProtocol.requireSnapshotId(snapshotId);
            MerkleProtocol.require(position != null, "Position is required");
            String configHash = MerkleProtocol.configurationHash(checkedAssets);
            MerkleProtocol.Node padding = MerkleProtocol.paddingNode(snapshotId, checkedAssets, configHash);
            return new Step(position, padding.hash(), padding.balances());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Merkle padding context", invalid);
        }
    }

    /** 验证个人证明 JSON 和独立公布的根信息 JSON；格式错误或校验失败返回 false。 */
    public static boolean verify(String proofJson, String anchorJson) {
        try {
            return verify(MerkleJson.proof(proofJson), MerkleJson.anchor(anchorJson));
        } catch (IllegalArgumentException invalidJson) {
            return false;
        }
    }

    /**
     * 根据外部可信批次信息验证完整证明。格式错误、字段缺失或任一校验失败均返回 false。
     * 真实全零叶及全零整树与其他树遵循相同的验证步骤。
     *
     * @param proof 待验证的叶子及路径，金额必须已是协议规范形式
     * @param anchor 从独立公布渠道取得的批次根、配置、真实叶子数及用户合计
     * @return 叶子、路径、根哈希与根总额全部一致时返回 true
     */
    public static boolean verify(MerkleProof proof, PublicAnchor anchor) {
        try {
            if (proof == null || anchor == null || proof.self() == null || proof.path() == null) {
                return false;
            }
            if (!MerkleProtocol.PROTOCOL.equals(proof.protocol())
                    || !MerkleProtocol.PROTOCOL.equals(anchor.protocol())) {
                return false;
            }
            List<Asset> assets = MerkleProtocol.checkedAssets(anchor.assets());
            if (!assets.equals(proof.assets())) {
                return false;
            }
            MerkleProtocol.requireSnapshotId(anchor.snapshotId());
            Self self = proof.self();
            if (!anchor.snapshotId().equals(self.snapshotId()) || anchor.leafCount() <= 0
                    || self.leafCount() != anchor.leafCount() || self.leafIndex() < 0
                    || self.leafIndex() >= self.leafCount()) {
                return false;
            }
            if (!MerkleProtocol.isHash(anchor.root()) || !MerkleProtocol.isHash(self.leafHash())
                    || !MerkleProtocol.isHash(self.accountId()) || !MerkleProtocol.isHash(self.nonce())) {
                return false;
            }

            Map<String, String> own = MerkleProtocol.canonical(
                    assets, self.balances(), MerkleProtocol.MAX_USER_AMOUNT_CHARS);
            Map<String, String> totals = MerkleProtocol.canonical(
                    assets, anchor.userTotals(), MerkleProtocol.MAX_SUM_AMOUNT_CHARS);
            String configHash = MerkleProtocol.configurationHash(assets);
            MerkleProtocol.Node current = MerkleProtocol.leafNode(
                    self.snapshotId(), assets, configHash, self.accountId(), self.nonce(), own);
            if (!current.hash().equals(self.leafHash())) {
                return false;
            }

            MerkleProtocol.Node padding = MerkleProtocol.paddingNode(self.snapshotId(), assets, configHash);
            int index = self.leafIndex();
            int width = self.leafCount();
            int used = 0;
            while (width > 1) {
                if (used >= proof.path().size()) {
                    return false;
                }
                Step step = proof.path().get(used++);
                Position expected = (index & 1) == 0 ? Position.RIGHT : Position.LEFT;
                if (step == null || step.position() != expected || !MerkleProtocol.isHash(step.hash())) {
                    return false;
                }
                MerkleProtocol.Node sibling = new MerkleProtocol.Node(step.hash(),
                        MerkleProtocol.canonical(assets, step.balances(), MerkleProtocol.MAX_SUM_AMOUNT_CHARS));
                // 仅宽度决定的缺位位置允许虚拟补零，且哈希和全部余额必须精确匹配。
                if ((index ^ 1) >= width
                        && (!sibling.hash().equals(padding.hash())
                        || !sibling.balances().equals(padding.balances()))) {
                    return false;
                }
                current = expected == Position.RIGHT
                        ? MerkleProtocol.parent(assets, configHash, current, sibling)
                        : MerkleProtocol.parent(assets, configHash, sibling, current);
                index /= 2;
                width = width / 2 + width % 2;
            }
            return used == proof.path().size() && current.hash().equals(anchor.root())
                    && current.balances().equals(totals);
        } catch (RuntimeException invalidProof) {
            return false;
        }
    }
}
