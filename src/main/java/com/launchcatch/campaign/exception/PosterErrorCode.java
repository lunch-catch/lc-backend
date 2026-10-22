package com.launchcatch.campaign.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 포스터와 템플릿 애그리거트의 실패 코드.
 * 명세는 docs/api-spec/poster-api-spec.md 의 "오류 코드" 절이다.
 */
@Getter
@RequiredArgsConstructor
public enum PosterErrorCode implements ErrorCode {

    SLOT_CONTRACT_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-001", "템플릿에 필요한 슬롯이 빠져 있거나 중복돼 있습니다."),
    STRUCTURE_CHANGED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-002", "수정한 템플릿의 구조가 이전 버전과 다릅니다."),
    TEMPLATE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-003", "템플릿 생성 한도에 도달해 더 만들 수 없습니다."),
    GENERATION_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "POSTER-004", "템플릿 생성이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "POSTER-005", "템플릿을 찾을 수 없습니다."),
    TEMPLATE_NOT_DRAFT(HttpStatus.CONFLICT, "POSTER-006", "게시된 템플릿은 수정하거나 삭제할 수 없습니다."),
    TEMPLATE_ALREADY_PUBLISHED(HttpStatus.CONFLICT, "POSTER-007", "이미 게시된 템플릿은 다시 게시할 수 없습니다."),
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "POSTER-008", "존재하지 않는 버전 번호로 게시할 수 없습니다."),
    TEMPLATE_NOT_PUBLISHED(HttpStatus.CONFLICT, "POSTER-009", "게시되지 않은 템플릿은 활성화할 수 없습니다."),
    GENERATION_TRUNCATED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-010", "템플릿 생성 결과가 잘렸습니다. 다시 시도해 주세요."),
    DISALLOWED_ELEMENT(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-011", "템플릿에 허용되지 않은 태그나 속성이 있습니다."),
    GENERATION_THROTTLED(HttpStatus.SERVICE_UNAVAILABLE, "POSTER-012", "템플릿 생성 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요."),
    GENERATION_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-013", "요청한 문구로는 템플릿을 생성할 수 없습니다. 다른 표현으로 다시 시도해 주세요."),
    GENERATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "POSTER-014", "템플릿 생성 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."),
    TEMPLATE_NOT_USABLE_FOR_POSTER(HttpStatus.CONFLICT, "POSTER-015", "게시되지 않았거나 활성화되지 않은 템플릿으로는 포스터를 만들 수 없습니다."),
    POSTER_IMAGE_NOT_UPLOADED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-016", "이미지가 실제로 업로드되지 않았습니다."),
    POSTER_IMAGE_CONTENT_TYPE_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-017", "jpg, png 파일만 업로드할 수 있습니다."),
    POSTER_IMAGE_FILE_TOO_LARGE(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-018", "파일 크기는 10MB를 넘을 수 없습니다."),
    POSTER_IMAGE_OBJECT_KEY_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-019", "이 캠페인의 업로드 요청으로 받은 객체 키가 아닙니다."),
    CAMPAIGN_PERIOD_NOT_SET(HttpStatus.CONFLICT, "POSTER-020", "집행 기간을 먼저 설정한 뒤 포스터를 만들어 주세요."),
    POSTER_NOT_SAVED(HttpStatus.NOT_FOUND, "POSTER-021", "이 캠페인에 저장된 포스터가 없습니다."),
    POSTER_UNAVAILABLE(HttpStatus.NOT_FOUND, "POSTER-022", "포스터를 조회할 수 없습니다."),
    PROMPT_TOO_SHORT(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-023",
            "요청 문구가 너무 짧습니다. 원하는 템플릿의 분위기나 색, 배치를 조금 더 자세히 적어 주세요."),
    PROMPT_INVALID_CHARACTERS(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-024",
            "요청 문구에 사용할 수 없는 문자가 포함되어 있습니다."),
    PROMPT_INJECTION_SUSPECTED(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-025",
            "요청 문구에 템플릿 생성과 관련 없는 지시가 포함되어 있습니다. 만들고 싶은 템플릿의 모양만 적어 주세요."),
    PROMPT_PERSONAL_INFO(HttpStatus.UNPROCESSABLE_ENTITY, "POSTER-026",
            "요청 문구에 개인정보로 보이는 내용이 포함되어 있습니다. 전화번호, 사업자번호 같은 정보는 빼고 다시 요청해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
