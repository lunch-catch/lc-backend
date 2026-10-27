package com.lunchcatch.campaign.poster.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * 점주가 포스터의 가게명과 할인 문구 뒤 텍스트, 글자 크기를 고치는 요청이다(#884). 부분 수정이라 보내지 않은
 * 필드는 바꾸지 않는다. 시간(period)과 이미지는 받지 않는다. 길이와 글자 크기의 범위는 서비스가 검사한다.
 */
public record PosterUpdateRequest(
        @Schema(description = "가게명 슬롯의 수정 값입니다. 보내지 않으면 바꾸지 않습니다.")
        EventName eventName,

        @Schema(description = "할인 슬롯의 수정 값입니다. 보내지 않으면 바꾸지 않습니다.")
        Discount discount
) {

    public record EventName(
            @Schema(description = "가게명 텍스트입니다. 앞뒤 공백을 자른 뒤 1~30자이고 줄바꿈과 제어 문자는 쓸 수 없습니다.",
                    example = "점심 특가 분식")
            String text,

            @Schema(description = "가게명 글자 크기(px)입니다. 16~48입니다.", example = "32")
            Integer fontSizePx
    ) {
    }

    public record Discount(
            @Schema(description = "할인 기본 문구 뒤에 붙일 텍스트입니다. 앞뒤 공백을 자른 뒤 0~20자이고 빈 문자열이면 지웁니다. "
                    + "줄바꿈과 제어 문자는 쓸 수 없습니다.", example = "(1인 1개)")
            String suffix,

            @Schema(description = "할인 글자 크기(px)입니다. 14~40입니다.", example = "26")
            Integer fontSizePx
    ) {
    }
}
