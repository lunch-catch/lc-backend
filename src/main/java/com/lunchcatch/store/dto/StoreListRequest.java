package com.lunchcatch.store.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record StoreListRequest(
        String query,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotEmpty List<@NotNull StoreCategoryFilter> filter,
        @Min(1) @Max(100) Integer pageSize,
        String pageToken
) {
    // 컨트롤러의 @Valid가 이 메서드를 JavaBeans getter로 인식하여 반환값이 true인지 자동 검증한다.
    @AssertTrue(message = "검색어는 빈 문자열이나 공백만으로 구성할 수 없습니다.")
    public boolean isQueryValid() {
        return query == null || !query.isBlank();
    }

    // 필수 여부는 @NotEmpty가 검사하며 여기서는 ALL과 개별 카테고리의 혼용만 거부한다.
    @AssertTrue(message = "ALL과 개별 카테고리를 함께 선택할 수 없습니다.")
    public boolean isFilterValid() {
        return filter == null || !filter.contains(StoreCategoryFilter.ALL)
                || filter.stream().allMatch(category -> category == StoreCategoryFilter.ALL);
    }
}
