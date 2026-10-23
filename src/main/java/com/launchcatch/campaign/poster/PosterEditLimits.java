package com.launchcatch.campaign.poster;

/*
 * 점주가 포스터 텍스트와 글자 크기를 수정할 때의 허용 범위다(#884). 서버가 이 값으로 입력을 거부하고, 같은 값을
 * poster-api-spec.md 의 표에 적어 프론트가 그 값으로 입력을 제한한다. 값을 바꾸면 그 표도 함께 고친다.
 *
 * 길이는 유니코드 코드 포인트로 센다(이모지 하나가 1이다). 글자 크기는 px 정수다.
 */
public final class PosterEditLimits {

    public static final int EVENT_NAME_MIN_LENGTH = 1;
    public static final int EVENT_NAME_MAX_LENGTH = 30;
    public static final int DISCOUNT_SUFFIX_MIN_LENGTH = 0;
    public static final int DISCOUNT_SUFFIX_MAX_LENGTH = 20;

    public static final int EVENT_NAME_FONT_SIZE_MIN = 16;
    public static final int EVENT_NAME_FONT_SIZE_MAX = 48;
    public static final int DISCOUNT_FONT_SIZE_MIN = 14;
    public static final int DISCOUNT_FONT_SIZE_MAX = 40;

    private PosterEditLimits() {
    }
}
