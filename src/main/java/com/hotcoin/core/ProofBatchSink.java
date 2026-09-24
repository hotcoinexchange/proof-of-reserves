package com.hotcoin.core;

import com.hotcoin.model.ProofRecord;

import java.io.IOException;
import java.util.List;

/** 同步接收一批完整证明；返回后才继续生产，写入失败必须向调用方抛出异常。 */
public interface ProofBatchSink {
    void write(List<ProofRecord> records) throws IOException;
}
