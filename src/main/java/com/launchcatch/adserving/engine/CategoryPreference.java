package com.launchcatch.adserving.engine;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * 사용자의 최근 30일 찜에서 업종마다 차지한 비율이다. 관련성 점수의 카테고리 선호도 항목이 된다.
 * 찜이 없으면 모든 업종이 0 이다.
 */
public record CategoryPreference(Map<String, Double> shares) {

    public static final CategoryPreference NONE = new CategoryPreference(Map.of());

    public CategoryPreference {
        shares = Map.copyOf(shares);
    }

    // 찜한 카드의 업종 코드를 찜 한 건마다 하나씩 받는다
    public static CategoryPreference fromWishedCategories(Collection<String> wishedCategories) {
        if (wishedCategories.isEmpty()) {
            return NONE;
        }
        double total = wishedCategories.size();
        Map<String, Double> shares = wishedCategories.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() / total));
        return new CategoryPreference(shares);
    }

    double share(String categoryCode) {
        return shares.getOrDefault(categoryCode, 0.0);
    }
}
