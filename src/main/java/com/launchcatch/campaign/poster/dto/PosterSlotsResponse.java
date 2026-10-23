package com.launchcatch.campaign.poster.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * 점주용 포스터 응답에 담는 슬롯별 현재 값이다(#884). 편집 화면이 완성 HTML 을 파싱하지 않고 입력칸을 채우게 한다.
 */
public record PosterSlotsResponse(
        @Schema(description = "가게명 슬롯의 현재 값입니다.")
        EventName eventName,

        @Schema(description = "할인 슬롯의 현재 값입니다.")
        Discount discount,

        @Schema(description = "시간 슬롯의 현재 값입니다. 수정할 수 없습니다.")
        Period period
) {

    public record EventName(
            @Schema(description = "화면에 나가는 가게명 텍스트입니다.", example = "런치캐치 시연 분식")
            String text,

            @Schema(description = "글자 크기(px)입니다. 템플릿이 px 로 정하지 않았으면 null 입니다.", example = "32")
            Integer fontSizePx
    ) {
    }

    public record Discount(
            @Schema(description = "쿠폰 조건으로 만든 기본 문구입니다.", example = "참치 김밥 2000원 할인")
            String baseText,

            @Schema(description = "기본 문구 뒤에 붙인 텍스트입니다. 없으면 빈 문자열입니다.", example = "(1인 1개)")
            String suffix,

            @Schema(description = "글자 크기(px)입니다. 템플릿이 px 로 정하지 않았으면 null 입니다.", example = "26")
            Integer fontSizePx
    ) {
    }

    public record Period(
            @Schema(description = "화면에 나가는 시간 문구입니다.", example = "매일 11:30 ~ 15:00")
            String text
    ) {
    }
}
