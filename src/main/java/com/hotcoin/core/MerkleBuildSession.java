package com.hotcoin.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.hotcoin.model.Asset;
import com.hotcoin.model.LeafInput;
import com.hotcoin.model.MerkleLeafRecord;
import com.hotcoin.model.MerkleNodeRecord;
import com.hotcoin.model.ProofRecord;
import com.hotcoin.model.PublicAnchor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 按输入顺序增量建树，并借助会话独占的临时文件批量导出完整证明。
 * 会话方法不能并发调用。仅保留固定计算窗口和每层一个待合并节点，不随叶子数量累积内存。
 * 任何操作失败后会话不可继续使用；调用方应始终使用 try-with-resources。
 */
public final class MerkleBuildSession implements AutoCloseable {
    private static final int PARALLELISM = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    private static final int LEAVES_PER_TASK = 256;
    private static final int BLOCK_HEIGHT = Integer.numberOfTrailingZeros(LEAVES_PER_TASK);
    private static final int JSON_BATCH_SIZE = 1024;
    private static final JsonFactory JSON = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final MerkleComputation computation;
    private final MerkleComputation.Worker coordinator;
    private final MerkleFiles files;
    private final LeafBatchSink directLeafSink;
    private final NodeBatchSink directNodeSink;
    private final List<MerkleLeafRecord> directLeaves;
    private final List<MerkleNodeRecord> directNodes;
    private final int directBatchSize;
    private final MerkleComputation.Node[] pending = new MerkleComputation.Node[32];
    private final int[] nodeCounts = new int[32];
    private final BigInteger[] directTotals;
    private final ExecutorService executor;
    private final ThreadLocal<MerkleComputation.Worker> workers;
    private State state = State.OPEN;
    private int leafCount;
    private int height;

    MerkleBuildSession(String snapshotId, List<Asset> assets, Path workDir) throws IOException {
        this.computation = new MerkleComputation(snapshotId, assets);
        this.coordinator = computation.worker();
        this.directTotals = computation.zeroAmounts();
        this.workers = ThreadLocal.withInitial(computation::worker);
        this.files = new MerkleFiles(workDir, computation.assets().size());
        this.directLeafSink = null;
        this.directNodeSink = null;
        this.directLeaves = null;
        this.directNodes = null;
        this.directBatchSize = 0;
        try {
            this.executor = PARALLELISM == 1 ? null : Executors.newFixedThreadPool(PARALLELISM, runnable -> {
                Thread thread = new Thread(runnable, "merkle-leaf-worker");
                thread.setDaemon(true);
                return thread;
            });
        } catch (RuntimeException | Error failure) {
            try {
                files.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    MerkleBuildSession(String snapshotId, List<Asset> assets, int batchSize,
                       LeafBatchSink leafSink, NodeBatchSink nodeSink) {
        MerkleProtocol.require(batchSize > 0, "Batch size must be positive");
        this.computation = new MerkleComputation(snapshotId, assets);
        this.coordinator = computation.worker();
        this.directTotals = computation.zeroAmounts();
        this.workers = ThreadLocal.withInitial(computation::worker);
        this.files = null;
        this.directLeafSink = Objects.requireNonNull(leafSink, "Leaf sink is required");
        this.directNodeSink = Objects.requireNonNull(nodeSink, "Node sink is required");
        this.directBatchSize = batchSize;
        this.directLeaves = new ArrayList<>(batchSize);
        this.directNodes = new ArrayList<>(batchSize);
        this.executor = PARALLELISM == 1 ? null : Executors.newFixedThreadPool(PARALLELISM, runnable -> {
            Thread thread = new Thread(runnable, "merkle-leaf-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 按列表顺序追加用户；内部只提交固定大小的计算窗口，不缓存调用方的完整列表。 */
    public void appendBatch(List<LeafInput> leaves) throws IOException {
        try {
            requireState(State.OPEN);
            Objects.requireNonNull(leaves, "Leaves are required");
            MerkleProtocol.require(leaves.size() <= Integer.MAX_VALUE - leafCount, "Too many leaves");
            if (executor == null || leaves.size() < LEAVES_PER_TASK) {
                for (LeafInput leaf : leaves) {
                    register(leaf);
                    accept(leaf, coordinator.leaf(leaf));
                }
                return;
            }
            int offset = 0;
            // 只有全局叶子索引对齐的完整 2 次幂块才能独立建子树；跨页前缀先按原顺序补齐。
            while (offset < leaves.size() && leafCount % LEAVES_PER_TASK != 0) {
                LeafInput leaf = leaves.get(offset++);
                register(leaf);
                accept(leaf, coordinator.leaf(leaf));
            }
            while (leaves.size() - offset >= LEAVES_PER_TASK) {
                List<BlockWork> tasks = new ArrayList<>(PARALLELISM);
                for (int task = 0; task < PARALLELISM && leaves.size() - offset >= LEAVES_PER_TASK; task++) {
                    int end = offset + LEAVES_PER_TASK;
                    List<LeafInput> chunk = new ArrayList<>(leaves.subList(offset, end));
                    for (LeafInput leaf : chunk) {
                        register(leaf);
                    }
                    Future<BlockNodes> future = executor.submit(() -> computeBlock(chunk));
                    tasks.add(new BlockWork(chunk, future));
                    offset = end;
                }
                // 按提交顺序消费，线程完成先后不会改变 leafIndex 或根哈希。
                for (BlockWork task : tasks) {
                    acceptBlock(task.leaves(), await(task.future()));
                }
            }
            // 尾块不独立补零；后续 append 仍可继续与它合并，只有 finish 才处理 padding。
            while (offset < leaves.size()) {
                LeafInput leaf = leaves.get(offset++);
                register(leaf);
                accept(leaf, coordinator.leaf(leaf));
            }
        } catch (IOException | RuntimeException | Error failure) {
            abort(failure);
            throw failure;
        }
    }

    /** 消费并关闭一个 JSON 用户数组输入流；严格要求金额为字符串，不接受未知或重复字段。 */
    public void appendJson(InputStream input) throws IOException {
        try (InputStream owned = input) {
            requireState(State.OPEN);
            Objects.requireNonNull(owned, "JSON input is required");
            try (JsonParser parser = JSON.createParser(owned).disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)) {
                requireToken(parser.nextToken(), JsonToken.START_ARRAY);
                List<LeafInput> batch = new ArrayList<>(JSON_BATCH_SIZE);
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    batch.add(readLeaf(parser));
                    if (batch.size() == JSON_BATCH_SIZE) {
                        appendBatch(batch);
                        batch.clear();
                    }
                }
                if (parser.nextToken() != null) {
                    throw invalidJson();
                }
                appendBatch(batch);
            } catch (JsonProcessingException invalid) {
                throw invalidJson();
            }
        } catch (IOException | RuntimeException | Error failure) {
            abort(failure);
            throw failure;
        }
    }

    /** 结束输入，按原协议补齐各层奇数尾节点，并返回可供业务侧保存的根及用户资产合计。 */
    public PublicAnchor finish() throws IOException {
        try {
            requireState(State.OPEN);
            MerkleProtocol.require(leafCount > 0, "Leaves must not be empty");
            height = 32 - Integer.numberOfLeadingZeros(leafCount - 1);
            for (int level = 0; level < height; level++) {
                if (pending[level] != null) {
                    MerkleComputation.Node left = pending[level];
                    pending[level] = null;
                    push(level + 1, coordinator.parent(left, computation.padding()));
                }
            }
            MerkleComputation.Node root = pending[height];
            if (root == null || !Arrays.equals(root.amounts(), directTotals)) {
                throw new IllegalStateException("Tree totals differ from direct totals");
            }
            for (int level = 0; level <= height; level++) {
                long expected = ((long) leafCount + (1L << level) - 1) >> level;
                if (nodeCounts[level] != expected) {
                    throw new IllegalStateException("Unexpected tree level size");
                }
            }
            if (files != null) {
                files.finishWrites();
            } else {
                flushDirect();
            }
            PublicAnchor anchor = computation.anchor(root, leafCount);
            Arrays.fill(pending, null);
            if (executor != null) {
                executor.shutdown();
            }
            state = State.FINISHED;
            return anchor;
        } catch (IOException | RuntimeException | Error failure) {
            abort(failure);
            throw failure;
        }
    }

    /**
     * 按 leafIndex 顺序同步导出；数量和 UTF-8 字节数任一达到上限即发送。
     * 单份证明若超过 maxBytes，会作为独占的一批发送，不截断、不丢弃。
     * 成功完成后可再次导出；sink 或文件失败会终止并清理会话。
     */
    public void exportProofs(int maxRecords, int maxBytes, ProofBatchSink sink) throws IOException {
        try {
            requireState(State.FINISHED);
            if (files == null) {
                throw new IllegalStateException("Proof export is unavailable for a streaming session");
            }
            MerkleProtocol.require(maxRecords > 0 && maxBytes > 0, "Batch limits must be positive");
            Objects.requireNonNull(sink, "Proof sink is required");
            state = State.EXPORTING;
            try (MerkleFiles.Readers readers = files.readers(height)) {
                PairCursor[] cursors = new PairCursor[Math.max(1, height)];
                for (int level = 0; level < cursors.length; level++) {
                    cursors[level] = new PairCursor(readers, level);
                }
                String prefix = proofPrefix();
                List<ProofRecord> batch = new ArrayList<>(Math.min(maxRecords, 1024));
                long bytes = 0;
                for (int leafIndex = 0; leafIndex < leafCount; leafIndex++) {
                    for (PairCursor cursor : cursors) {
                        cursor.advance(leafIndex);
                    }
                    String json = proofJson(prefix, readers.metadata(), leafIndex, cursors);
                    // 协议限定所有字段为 ASCII，因此字符数等于紧凑 JSON 的 UTF-8 字节数。
                    int size = json.length();
                    if (!batch.isEmpty() && (batch.size() == maxRecords || bytes + size > maxBytes)) {
                        sink.write(immutableCopy(batch));
                        batch.clear();
                        bytes = 0;
                    }
                    batch.add(new ProofRecord(leafIndex, json));
                    bytes += size;
                    if (batch.size() == maxRecords || bytes >= maxBytes) {
                        sink.write(immutableCopy(batch));
                        batch.clear();
                        bytes = 0;
                    }
                }
                readers.requireEnd();
                if (!batch.isEmpty()) {
                    sink.write(immutableCopy(batch));
                }
            }
            state = State.FINISHED;
        } catch (IOException | RuntimeException | Error failure) {
            abort(failure);
            throw failure;
        }
    }

    /**
     * 线性导出真实叶子和内部节点。根节点已由 finish() 返回，不重复发送给 nodeSink。
     * 每条记录只导出一次，适合业务侧保存树结构，避免生成 N 份 O(logN) 完整证明。
     */
    public void exportTree(int batchSize, LeafBatchSink leafSink, NodeBatchSink nodeSink) throws IOException {
        try {
            requireState(State.FINISHED);
            if (files == null) {
                throw new IllegalStateException("Tree export is unavailable for a streaming session");
            }
            MerkleProtocol.require(batchSize > 0, "Batch size must be positive");
            Objects.requireNonNull(leafSink, "Leaf sink is required");
            Objects.requireNonNull(nodeSink, "Node sink is required");
            state = State.EXPORTING;
            try (MerkleFiles.Readers readers = files.readers(height + 1)) {
                List<MerkleLeafRecord> leaves = new ArrayList<>(Math.min(batchSize, leafCount));
                for (int index = 0; index < leafCount; index++) {
                    MerkleFiles.LeafMetadata metadata = readers.metadata();
                    MerkleComputation.Node node = readers.node(0);
                    leaves.add(new MerkleLeafRecord(index, metadata.accountId(), metadata.nonce(),
                            node.hash(), computation.amounts(node.amounts())));
                    if (leaves.size() == batchSize) {
                        leafSink.write(immutableCopy(leaves));
                        leaves.clear();
                    }
                }
                if (!leaves.isEmpty()) {
                    leafSink.write(immutableCopy(leaves));
                }

                List<MerkleNodeRecord> nodes = new ArrayList<>(batchSize);
                for (int level = 1; level <= height; level++) {
                    for (int index = 0; index < nodeCounts[level]; index++) {
                        MerkleComputation.Node node = readers.node(level);
                        if (level == height) {
                            continue;
                        }
                        nodes.add(new MerkleNodeRecord(level, index, node.hash(),
                                computation.amounts(node.amounts())));
                        if (nodes.size() == batchSize) {
                            nodeSink.write(immutableCopy(nodes));
                            nodes.clear();
                        }
                    }
                }
                if (!nodes.isEmpty()) {
                    nodeSink.write(immutableCopy(nodes));
                }
                readers.requireEnd();
            }
            state = State.FINISHED;
        } catch (IOException | RuntimeException | Error failure) {
            abort(failure);
            throw failure;
        }
    }

    private void register(LeafInput leaf) {
        MerkleProtocol.require(leaf != null, "Leaf is required");
        // 完整字符校验由计算线程执行；此处只做常量开销的前置检查，避免重复匹配两次哈希。
        MerkleProtocol.require(leaf.accountId() != null && leaf.accountId().length() == 64, "Invalid account ID");
        MerkleProtocol.require(leaf.nonce() != null && leaf.nonce().length() == 64, "Invalid nonce");
    }

    private void accept(LeafInput leaf, MerkleComputation.Node node) throws IOException {
        MerkleProtocol.require(leafCount < Integer.MAX_VALUE, "Too many leaves");
        writeLeaf(leaf, node, leafCount);
        computation.accumulate(directTotals, node.amounts());
        push(0, node);
        leafCount++;
    }

    private BlockNodes computeBlock(List<LeafInput> leaves) {
        MerkleComputation.Worker worker = workers.get();
        List<List<MerkleComputation.Node>> levels = new ArrayList<>(BLOCK_HEIGHT + 1);
        List<MerkleComputation.Node> current = new ArrayList<>(LEAVES_PER_TASK);
        for (LeafInput leaf : leaves) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Merkle computation interrupted");
            }
            current.add(worker.leaf(leaf));
        }
        levels.add(current);
        while (current.size() > 1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Merkle computation interrupted");
            }
            List<MerkleComputation.Node> parents = new ArrayList<>(current.size() / 2);
            for (int index = 0; index < current.size(); index += 2) {
                parents.add(worker.parent(current.get(index), current.get(index + 1)));
            }
            levels.add(parents);
            current = parents;
        }
        return new BlockNodes(levels);
    }

    private void acceptBlock(List<LeafInput> leaves, BlockNodes block) throws IOException {
        if (leafCount % LEAVES_PER_TASK != 0) {
            throw new IllegalStateException("Unaligned Merkle block");
        }
        for (int level = 0; level < BLOCK_HEIGHT; level++) {
            if (pending[level] != null) {
                throw new IllegalStateException("Unmerged Merkle block prefix");
            }
        }
        List<MerkleComputation.Node> inputs = block.levels().get(0);
        for (int index = 0; index < leaves.size(); index++) {
            LeafInput leaf = leaves.get(index);
            writeLeaf(leaf, inputs.get(index), leafCount + index);
            // 直接合计仍逐叶独立相加，不复用子树根合计作为校验值。
            computation.accumulate(directTotals, inputs.get(index).amounts());
        }
        for (int level = 0; level < BLOCK_HEIGHT; level++) {
            for (MerkleComputation.Node node : block.levels().get(level)) {
                writeNode(level, node);
                nodeCounts[level]++;
            }
        }
        push(BLOCK_HEIGHT, block.levels().get(BLOCK_HEIGHT).get(0));
        leafCount += LEAVES_PER_TASK;
    }

    private void push(int level, MerkleComputation.Node node) throws IOException {
        while (true) {
            writeNode(level, node);
            nodeCounts[level]++;
            if (pending[level] == null) {
                pending[level] = node;
                return;
            }
            MerkleComputation.Node left = pending[level];
            pending[level] = null;
            node = coordinator.parent(left, node);
            level++;
        }
    }

    private void writeLeaf(LeafInput leaf, MerkleComputation.Node node, int index) throws IOException {
        if (files != null) {
            files.writeMetadata(leaf.accountId(), leaf.nonce());
            return;
        }
        directLeaves.add(new MerkleLeafRecord(index, leaf.accountId(), leaf.nonce(),
                node.hash(), computation.amounts(node.amounts())));
        if (directLeaves.size() == directBatchSize) {
            directLeafSink.write(immutableCopy(directLeaves));
            directLeaves.clear();
        }
    }

    private void writeNode(int level, MerkleComputation.Node node) throws IOException {
        if (files != null) {
            files.writeNode(level, node);
            return;
        }
        if (level == 0) {
            return;
        }
        directNodes.add(new MerkleNodeRecord(level, nodeCounts[level], node.hash(),
                computation.amounts(node.amounts())));
        if (directNodes.size() == directBatchSize) {
            directNodeSink.write(immutableCopy(directNodes));
            directNodes.clear();
        }
    }

    private void flushDirect() throws IOException {
        if (!directLeaves.isEmpty()) {
            directLeafSink.write(immutableCopy(directLeaves));
            directLeaves.clear();
        }
        if (!directNodes.isEmpty()) {
            directNodeSink.write(immutableCopy(directNodes));
            directNodes.clear();
        }
    }

    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Merkle computation interrupted");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Merkle computation failed");
        }
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    private String proofPrefix() {
        StringBuilder json = new StringBuilder("{\"protocol\":\"").append(MerkleProtocol.PROTOCOL).append("\",\"assets\":[");
        for (int index = 0; index < computation.assets().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Asset asset = computation.assets().get(index);
            json.append("{\"coin\":\"").append(asset.coin()).append("\",\"decimals\":").append(asset.decimals()).append('}');
        }
        return json.append("],\"self\":{\"snapshotId\":\"").append(computation.snapshotId())
                .append("\",\"accountId\":\"").toString();
    }

    private String proofJson(String prefix, MerkleFiles.LeafMetadata metadata, int leafIndex, PairCursor[] cursors) {
        CachedNode own = cursors[0].own(leafIndex);
        StringBuilder json = new StringBuilder(512 + height * 256).append(prefix)
                .append(metadata.accountId()).append("\",\"nonce\":\"").append(metadata.nonce())
                .append("\",\"balances\":").append(own.balances()).append(",\"leafHash\":\"").append(own.hash())
                .append("\",\"leafIndex\":").append(leafIndex).append(",\"leafCount\":").append(leafCount).append("},\"path\":[");
        for (int level = 0; level < height; level++) {
            if (level > 0) {
                json.append(',');
            }
            json.append(cursors[level].sibling(leafIndex).step());
        }
        return json.append("]}").toString();
    }

    private CachedNode cached(MerkleComputation.Node node, String position) {
        StringBuilder balances = new StringBuilder("{");
        for (int index = 0; index < computation.assets().size(); index++) {
            if (index > 0) {
                balances.append(',');
            }
            balances.append('"').append(computation.assets().get(index).coin()).append("\":\"")
                    .append(computation.amount(node.amounts()[index], index)).append('"');
        }
        String values = balances.append('}').toString();
        String step = "{\"position\":\"" + position + "\",\"hash\":\"" + node.hash() + "\",\"balances\":" + values + "}";
        return new CachedNode(node.hash(), values, step);
    }

    private final class PairCursor {
        private final MerkleFiles.Readers readers;
        private final int level;
        private int pairIndex = -1;
        private CachedNode left;
        private CachedNode right;

        private PairCursor(MerkleFiles.Readers readers, int level) {
            this.readers = readers;
            this.level = level;
        }

        private void advance(int leafIndex) throws IOException {
            int expected = (leafIndex >> level) >> 1;
            if (expected != pairIndex) {
                if (expected != pairIndex + 1) {
                    throw new IllegalStateException("Proof export must be sequential");
                }
                left = cached(readers.node(level), "LEFT");
                right = expected * 2L + 1 < nodeCounts[level]
                        ? cached(readers.node(level), "RIGHT") : cached(computation.padding(), "RIGHT");
                pairIndex = expected;
            }
        }

        private CachedNode own(int leafIndex) {
            return ((leafIndex >> level) & 1) == 0 ? left : right;
        }

        private CachedNode sibling(int leafIndex) {
            return ((leafIndex >> level) & 1) == 0 ? right : left;
        }
    }

    private static LeafInput readLeaf(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT);
        String accountId = null;
        String nonce = null;
        Map<String, String> balances = null;
        int fields = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME);
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "accountId" -> accountId = anonymousValue(parser);
                case "nonce" -> nonce = anonymousValue(parser);
                case "balances" -> balances = readBalances(parser);
                default -> throw invalidJson();
            }
            fields++;
        }
        if (fields != 3) {
            throw invalidJson();
        }
        return new LeafInput(accountId, nonce, balances);
    }

    private static Map<String, String> readBalances(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT);
        Map<String, String> balances = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME);
            String coin = parser.currentName();
            if (coin.isEmpty() || coin.length() > 16) {
                throw invalidJson();
            }
            parser.nextToken();
            String amount = string(parser);
            if (balances.size() == 32 || amount.length() > MerkleProtocol.MAX_USER_AMOUNT_CHARS) {
                throw invalidJson();
            }
            balances.put(coin, amount);
        }
        return balances;
    }

    private static String string(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.VALUE_STRING);
        return parser.getText();
    }

    private static String anonymousValue(JsonParser parser) throws IOException {
        String value = string(parser);
        if (value.length() != 64) {
            throw invalidJson();
        }
        return value;
    }

    private static void requireToken(JsonToken actual, JsonToken expected) {
        if (actual != expected) {
            throw invalidJson();
        }
    }

    private static IllegalArgumentException invalidJson() {
        // 不保留 Jackson 原始异常，防止其上下文附带用户资产或输入内容。
        return new IllegalArgumentException("Invalid Merkle JSON");
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Merkle session is not " + expected);
        }
    }

    private void abort(Throwable failure) {
        state = State.FAILED;
        try {
            cleanup();
        } catch (IOException | RuntimeException closing) {
            failure.addSuppressed(closing);
        }
    }

    private void cleanup() throws IOException {
        if (executor != null) {
            executor.shutdownNow();
        }
        Arrays.fill(pending, null);
        if (files != null) {
            files.close();
        }
        if (directLeaves != null) {
            directLeaves.clear();
            directNodes.clear();
        }
    }

    /** 无论建树或导出是否成功，都关闭资源并删除本会话独占的临时子目录。 */
    @Override
    public void close() throws IOException {
        state = State.CLOSED;
        cleanup();
    }

    private enum State { OPEN, FINISHED, EXPORTING, FAILED, CLOSED }

    private static final class BlockWork {
        private final List<LeafInput> leaves;
        private final Future<BlockNodes> future;

        private BlockWork(List<LeafInput> leaves, Future<BlockNodes> future) {
            this.leaves = leaves;
            this.future = future;
        }

        private List<LeafInput> leaves() {
            return leaves;
        }

        private Future<BlockNodes> future() {
            return future;
        }
    }

    private static final class BlockNodes {
        private final List<List<MerkleComputation.Node>> levels;

        private BlockNodes(List<List<MerkleComputation.Node>> levels) {
            this.levels = levels;
        }

        private List<List<MerkleComputation.Node>> levels() {
            return levels;
        }
    }

    private static final class CachedNode {
        private final String hash;
        private final String balances;
        private final String step;

        private CachedNode(String hash, String balances, String step) {
            this.hash = hash;
            this.balances = balances;
            this.step = step;
        }

        private String hash() {
            return hash;
        }

        private String balances() {
            return balances;
        }

        private String step() {
            return step;
        }
    }
}
