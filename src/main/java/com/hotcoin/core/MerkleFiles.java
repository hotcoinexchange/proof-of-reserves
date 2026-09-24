package com.hotcoin.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** 会话独占的临时文件；哈希和匿名参数都使用完整的 64 位小写十六进制文本。 */
final class MerkleFiles implements AutoCloseable {
    private static final int BUFFER_BYTES = 128 * 1024;
    private static final int MAX_AMOUNT_BYTES = 64;

    private final Path directory;
    private final int assetCount;
    private final List<Path> ownedFiles = new ArrayList<>();
    private final DataOutputStream[] layers = new DataOutputStream[32];
    private DataOutputStream metadata;
    private boolean writesFinished;

    MerkleFiles(Path parent, int assetCount) throws IOException {
        if (parent == null) {
            throw new IllegalArgumentException("Working directory is required");
        }
        Path absoluteParent = parent.toAbsolutePath().normalize();
        Files.createDirectories(absoluteParent);
        this.directory = Files.createTempDirectory(absoluteParent, "merkle-").toAbsolutePath().normalize();
        this.assetCount = assetCount;
        try {
            metadata = output("leaf-meta.bin");
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void writeMetadata(String accountId, String nonce) throws IOException {
        metadata.write(accountId.getBytes(StandardCharsets.US_ASCII));
        metadata.write(nonce.getBytes(StandardCharsets.US_ASCII));
    }

    void writeNode(int level, MerkleComputation.Node node) throws IOException {
        DataOutputStream output = layers[level];
        if (output == null) {
            output = output("level-" + level + ".bin");
            layers[level] = output;
        }
        output.write(node.hash().getBytes(StandardCharsets.US_ASCII));
        for (BigInteger amount : node.amounts()) {
            if (amount.signum() == 0) {
                output.writeByte(1);
                output.writeByte(0);
                continue;
            }
            byte[] bytes = amount.toByteArray();
            int start = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
            int length = bytes.length - start;
            if (amount.signum() < 0 || length > MAX_AMOUNT_BYTES) {
                throw new IOException("Invalid stored amount");
            }
            output.writeByte(length);
            output.write(bytes, start, length);
        }
    }

    void finishWrites() throws IOException {
        IOException failure = closeOutputs();
        if (failure != null) {
            throw failure;
        }
        writesFinished = true;
    }

    Readers readers(int height) throws IOException {
        if (!writesFinished) {
            throw new IllegalStateException("Node files have not been finished");
        }
        return new Readers(Math.max(1, height));
    }

    private DataOutputStream output(String name) throws IOException {
        Path path = directory.resolve(name);
        ownedFiles.add(path);
        OutputStream raw;
        try {
            raw = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException | Error failure) {
            // CREATE_NEW 未成功时不取得该路径的所有权，不能清理碰巧已存在的文件。
            ownedFiles.remove(path);
            throw failure;
        }
        try {
            return new DataOutputStream(new BufferedOutputStream(raw, BUFFER_BYTES));
        } catch (RuntimeException | Error failure) {
            try {
                raw.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private DataInputStream input(String name) throws IOException {
        InputStream raw = Files.newInputStream(directory.resolve(name));
        try {
            return new DataInputStream(new BufferedInputStream(raw, BUFFER_BYTES));
        } catch (RuntimeException | Error failure) {
            try {
                raw.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private IOException closeOutputs() {
        IOException failure = null;
        if (metadata != null) {
            try {
                metadata.close();
            } catch (IOException closing) {
                failure = closing;
            } finally {
                metadata = null;
            }
        }
        for (int level = 0; level < layers.length; level++) {
            if (layers[level] != null) {
                try {
                    layers[level].close();
                } catch (IOException closing) {
                    failure = merge(failure, closing);
                } finally {
                    layers[level] = null;
                }
            }
        }
        return failure;
    }

    @Override
    public void close() throws IOException {
        IOException failure = closeOutputs();
        // 只删除本会话登记的直属文件及唯一子目录；从不递归删除传入的 parent。
        for (Path path : ownedFiles) {
            if (!path.toAbsolutePath().normalize().getParent().equals(directory)) {
                failure = merge(failure, new IOException("Unexpected temporary file path"));
                continue;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException deleting) {
                failure = merge(failure, deleting);
            }
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException deleting) {
            failure = merge(failure, deleting);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException merge(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    final class Readers implements AutoCloseable {
        private final DataInputStream[] nodes;
        private DataInputStream metadataInput;

        private Readers(int levels) throws IOException {
            nodes = new DataInputStream[levels];
            try {
                metadataInput = input("leaf-meta.bin");
                for (int level = 0; level < levels; level++) {
                    nodes[level] = input("level-" + level + ".bin");
                }
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    close();
                } catch (IOException closing) {
                    failure.addSuppressed(closing);
                }
                throw failure;
            }
        }

        LeafMetadata metadata() throws IOException {
            return new LeafMetadata(ascii(metadataInput, 64), ascii(metadataInput, 64));
        }

        MerkleComputation.Node node(int level) throws IOException {
            DataInputStream input = nodes[level];
            String hash = ascii(input, 64);
            BigInteger[] amounts = new BigInteger[assetCount];
            for (int coin = 0; coin < assetCount; coin++) {
                int length = input.readUnsignedByte();
                if (length < 1 || length > MAX_AMOUNT_BYTES) {
                    throw new IOException("Invalid node amount length");
                }
                if (length == 1) {
                    amounts[coin] = BigInteger.valueOf(input.readUnsignedByte());
                } else {
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    amounts[coin] = new BigInteger(1, bytes);
                }
            }
            return new MerkleComputation.Node(hash, amounts);
        }

        void requireEnd() throws IOException {
            if (metadataInput.read() != -1) {
                throw new IOException("Unexpected leaf metadata");
            }
            for (DataInputStream input : nodes) {
                if (input.read() != -1) {
                    throw new IOException("Unexpected tree node");
                }
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            if (metadataInput != null) {
                try {
                    metadataInput.close();
                } catch (IOException closing) {
                    failure = closing;
                }
            }
            for (DataInputStream input : nodes) {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException closing) {
                        failure = merge(failure, closing);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static String ascii(DataInputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Incomplete Merkle temporary file");
        }
        String value = new String(bytes, StandardCharsets.US_ASCII);
        if (!MerkleProtocol.isHash(value)) {
            throw new IOException("Invalid stored hash or anonymous value");
        }
        return value;
    }

    static final class LeafMetadata {
        private final String accountId;
        private final String nonce;

        LeafMetadata(String accountId, String nonce) {
            this.accountId = accountId;
            this.nonce = nonce;
        }

        String accountId() {
            return accountId;
        }

        String nonce() {
            return nonce;
        }
    }
}
