package com.launchcatch.campaign.poster;

/*
 * poster.slot_values JSON 의 슬롯 하나다. value 는 화면에 나가는 최종 문구(이미지 슬롯은 URL)이고 자동 검수가 이
 * 값을 본다. 나머지는 점주가 수정할 수 있게 된 뒤(#884)에 더한 선택 값이라 없을 수 있다. 이 기능 이전에 만든
 * 포스터에는 type 과 value 만 있다.
 *
 * baseText 는 수정하기 전의 자동 문구다(가게명은 가게 이름, 할인은 쿠폰 조건으로 만든 기본 문구). suffix 는 할인 기본
 * 문구 뒤에 붙인 점주의 텍스트이고, fontSizePx 는 점주가 정한 글자 크기다.
 */
public record PosterSlotValue(String type, String value, String baseText, String suffix, Integer fontSizePx) {

    public static PosterSlotValue of(String type, String value) {
        return new PosterSlotValue(type, value, null, null, null);
    }
}
