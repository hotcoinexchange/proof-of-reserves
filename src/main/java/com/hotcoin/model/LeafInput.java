package com.hotcoin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

/**
 * 一条真实叶子的输入，不含业务 UID。
 * accountId 与 nonce 均由调用方提供，要求为 64 位小写十六进制字符串。
 * balances 必须包含本批次全部币种；全零余额也是正常的真实叶子。
 */
public final class LeafInput {
    private final String accountId;
    private final String nonce;
    private final Map<String, String> balances;

    @JsonCreator
    public LeafInput(@JsonProperty("accountId") String accountId,
                     @JsonProperty("nonce") String nonce,
                     @JsonProperty("balances") Map<String, String> balances) {
        this.accountId = accountId;
        this.nonce = nonce;
        this.balances = ModelCopies.map(balances);
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LeafInput)) {
            return false;
        }
        LeafInput that = (LeafInput) other;
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(nonce, that.nonce)
                && Objects.equals(balances, that.balances);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, nonce, balances);
    }

    @Override
    public String toString() {
        return "LeafInput{" + "accountId='" + accountId + '\''
                + ", nonce='" + nonce + '\'' + ", balances=" + balances + '}';
    }
}
