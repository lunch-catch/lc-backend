package com.launchcatch.campaign.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 포스터와 템플릿 애그리거트의 실패 코드.
 * 명세는 docs/api-spec/poster.md 의 "오류 코드" 절이다.
 */
@Getter
@RequiredArgsConstructor
public enum PosterErrorCode implements ErrorCode {

    SLOT_CONTRACT_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-001", "템플릿에 필요한 슬롯이 빠져 있거나 중복돼 있습니다."),
    STRUCTURE_CHANGED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-002", "수정한 템플릿의 구조가 이전 버전과 다릅니다."),
    TEMPLATE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-003", "템플릿은 최대 10개까지 만들 수 있습니다."),
    GENERATION_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "POSTER-004", "템플릿 생성이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "POSTER-005", "템플릿을 찾을 수 없습니다."),
    TEMPLATE_NOT_DRAFT(HttpStatus.CONFLICT, "POSTER-006", "게시된 템플릿은 수정할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
