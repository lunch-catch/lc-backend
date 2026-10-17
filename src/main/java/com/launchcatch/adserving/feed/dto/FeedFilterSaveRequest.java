package com.launchcatch.adserving.feed.dto;

import com.launchcatch.adserving.engine.FeedSort;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/*
 * 정렬을 enum 으로 받으면 허용되지 않은 값이 본문 해석 실패(COMMON-003)로 가서, 명세의 COMMON-002 와 맞추려고 문자열로 받아 검사한다.
 * 업종 코드는 store 가 소유하고 contract 로 공개하지 않아 store.category_code 의 CHECK 와 같은 목록을 여기 둔다(#411 에서 임시로 정했다).
 */
public record FeedFilterSaveRequest(
        @ArraySchema(arraySchema = @Schema(description = "업종 코드 목록. 빈 배열은 전체 업종이다. 같은 코드가 두 번 오면 하나로 합친다", example = "[\"KOREAN\", \"JAPANESE\"]"))
        @NotNull
        List<@NotNull @Pattern(regexp = "^(KOREAN|CHINESE|JAPANESE|WESTERN|BUNSIK|ASIAN|FAST_FOOD|CAFE_DESSERT|OTHER)$") String> categories,
        @Schema(description = "DISTANCE(거리순), DISCOUNT_RATE(할인율순)", allowableValues = {"DISTANCE", "DISCOUNT_RATE"})
        @NotNull
        @Pattern(regexp = "^(DISTANCE|DISCOUNT_RATE)$")
        String sort
) {

    public FeedSort feedSort() {
        return FeedSort.valueOf(sort);
    }
}
