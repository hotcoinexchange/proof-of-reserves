package com.hotcoin.core;

import com.hotcoin.model.MerkleNodeRecord;

import java.io.IOException;
import java.util.List;

public interface NodeBatchSink {
    void write(List<MerkleNodeRecord> nodes) throws IOException;
}
