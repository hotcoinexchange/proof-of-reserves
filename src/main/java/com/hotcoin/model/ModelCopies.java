package com.hotcoin.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型容器的防御复制；保留 null 供建树及验证入口统一处理。 */
final class ModelCopies {
    private ModelCopies() {
    }

    static <T> List<T> list(List<T> input) {
        return input == null ? null : Collections.unmodifiableList(new ArrayList<>(input));
    }

    static <K, V> Map<K, V> map(Map<K, V> input) {
        return input == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
