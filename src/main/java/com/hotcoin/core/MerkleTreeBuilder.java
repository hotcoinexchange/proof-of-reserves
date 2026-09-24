package com.hotcoin.core;

import com.hotcoin.model.Asset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 无业务依赖的分批求和树构建入口。保持调用方的叶子顺序，不排序、不筛选、不生成随机值。
 * 相同的批次号、有序币种配置及叶子输入产生相同结果。
 */
public final class MerkleTreeBuilder {
    private MerkleTreeBuilder() {
    }

    /** 打开流式会话，内部自动设置并行度；临时文件只创建在 workDir 内的独占子目录中，close 时删除。 */
    public static MerkleBuildSession open(String snapshotId, List<Asset> assets, Path workDir) throws IOException {
        return new MerkleBuildSession(snapshotId, assets, workDir);
    }

    /**
     * 打开不使用本地临时文件的流式持久化会话。叶子和节点在计算过程中按批同步输出；
     * sink 返回前必须完成持久化，失败应抛出异常终止本次构建。
     */
    public static MerkleBuildSession openStreaming(String snapshotId, List<Asset> assets, int batchSize,
                                                   LeafBatchSink leafSink, NodeBatchSink nodeSink) {
        return new MerkleBuildSession(snapshotId, assets, batchSize, leafSink, nodeSink);
    }
}
