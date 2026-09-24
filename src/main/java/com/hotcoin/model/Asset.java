package com.hotcoin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 批次的币种及账本小数位数。币种配置的顺序属于协议，由调用方确定。
 * 合法性在建树或验证时统一检查，配置对象本身不依赖业务框架。
 */
public final class Asset {
    private final String coin;
    private final int decimals;

    @JsonCreator
    public Asset(@JsonProperty("coin") String coin,
                 @JsonProperty("decimals") int decimals) {
        this.coin = coin;
        this.decimals = decimals;
    }

    public String getCoin() {
        return coin;
    }

    public int getDecimals() {
        return decimals;
    }

    /** 保留算法模块原有调用方式，后续可逐步统一为 JavaBean getter。 */
    public String coin() {
        return coin;
    }

    public int decimals() {
        return decimals;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Asset)) {
            return false;
        }
        Asset that = (Asset) other;
        return decimals == that.decimals && Objects.equals(coin, that.coin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coin, decimals);
    }

    @Override
    public String toString() {
        return "Asset{" + "coin='" + coin + '\'' + ", decimals=" + decimals + '}';
    }
}
