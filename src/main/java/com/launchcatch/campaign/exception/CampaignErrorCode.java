package com.launchcatch.campaign.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 캠페인 애그리거트의 실패 코드. 명세는 docs/api-spec/campaign-api-spec.md 의 "오류 코드" 절이다.
 * 코드는 기능 이슈에서 하나씩 더한다. 접두어마다 번호가 1부터 끊기지 않아야 한다.
 */
@Getter
@RequiredArgsConstructor
public enum CampaignErrorCode implements ErrorCode {

    OWNER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "CAMPAIGN-001", "점주 상태가 ACTIVE가 아닙니다."),
    DISCOUNT_VALUE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-002", "할인율은 100 이하여야 합니다."),
    ISSUE_QUANTITY_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-003", "발급 수량은 1 이상이어야 합니다."),
    USABLE_TIME_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-004", "이용 가능 시간이 올바르지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
