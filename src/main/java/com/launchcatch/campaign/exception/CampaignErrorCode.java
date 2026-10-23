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
    DISCOUNT_VALUE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-002", "할인 설정이 올바르지 않습니다."),
    ISSUE_QUANTITY_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-003", "발급 수량은 1 이상이어야 합니다."),
    USABLE_TIME_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-004", "이용 가능 시간이 올바르지 않습니다."),
    CAMPAIGN_NOT_OWNED(HttpStatus.FORBIDDEN, "CAMPAIGN-005", "그 캠페인에 접근할 권한이 없습니다."),
    CAMPAIGN_STATE_NOT_ALLOWED(HttpStatus.CONFLICT, "CAMPAIGN-006", "지금 캠페인 상태에서는 할 수 없는 요청입니다."),
    DAILY_BUDGET_TOO_LOW(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-007", "하루 예산은 최소 하루 예산 이상이어야 합니다."),
    PERIOD_TOO_SHORT(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-008", "집행 기간은 1일 이상이어야 합니다."),
    START_DATE_TOO_EARLY(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-009", "집행 시작일은 오늘 다음 날 이후여야 합니다."),
    CAMPAIGN_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "CAMPAIGN-010", "이미 진행 중인 캠페인이 있어 새로 등록할 수 없습니다."),
    ACTIVATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "CAMPAIGN-011", "캠페인 활성화 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."),
    CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "CAMPAIGN-012", "캠페인을 찾을 수 없습니다."),
    STORE_NOT_OWNED(HttpStatus.FORBIDDEN, "CAMPAIGN-013", "그 가게에 접근할 권한이 없습니다."),
    TARGET_MENU_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN-014",
            "할인 대상 메뉴를 사용할 수 없습니다. 이 가게의 메뉴인지와 메뉴 이미지를 확인해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
