package com.hotcoin.core;

import com.hotcoin.model.MerkleLeafRecord;

import java.io.IOException;
import java.util.List;

public interface LeafBatchSink {
    void write(List<MerkleLeafRecord> leaves) throws IOException;
}
