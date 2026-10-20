package com.launchcatch.campaign.poster.entity;

/*
 * 검수 FAIL 사유다. poster_moderation_result.reason 에는 이 이름을 쉼표로 이어 저장한다.
 * 화면 문구는 이 코드를 받는 쪽(캠페인 활성화)이 붙인다.
 */
public enum ModerationFailReason {
    FORBIDDEN_EXPRESSION,
    IMAGE_UNREADABLE,
    RESOLUTION_TOO_LOW,
    TEXT_RATIO_EXCEEDED,
    MODERATION_TOOL_FAILURE
}
