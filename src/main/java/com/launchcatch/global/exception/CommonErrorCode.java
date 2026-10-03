package com.launchcatch.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 특정 도메인에 속하지 않는 공통 오류 코드.
 * 도메인 지식이 필요 없는 프레임워크 경계의 실패만 둔다.
 * 회원을 못 찾았다거나 선착순 수량이 모자란다는 것은 도메인이 아는 실패라 각 도메인의 ErrorCode 로 간다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    /*
     * 어느 분기로도 잡히지 않은 예외다.
     * 내부 상세를 감추고 이것으로 응답한다.
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-001", "서버 오류가 발생했습니다."),

    // Bean Validation 실패 (MethodArgumentNotValidException, BindException, ConstraintViolationException)
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-002", "입력값이 올바르지 않습니다. 요청 형식을 확인해 주세요."),

    /*
     * 요청을 읽는 단계에서 깨진 것이라 검증까지 가지 못한 경우다.
     * HttpMessageNotReadableException, MethodArgumentTypeMismatchException, MissingServletRequestParameterException
     */
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "COMMON-003", "요청을 해석할 수 없습니다. 본문과 파라미터 형식을 확인해 주세요."),

    /*
     * 자격 증명이 없거나 유효하지 않다. AuthenticationException
     *
     * 지금 이 코드로 응답하는 자리는 없다. auth.md 가 그 실패를 AUTH-006 으로 못 박아
     * auth.exception.AuthExceptionHandler 가 답한다. 번호를 지우지 않는 이유는 둘이다.
     * 공개된 계약이라 한 번 매긴 번호는 다른 뜻으로 돌려 쓰지 않고, 번호가 1부터 끊기지
     * 않아야 한다는 규칙을 ErrorCodeCatalogTest 가 지킨다.
     */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "COMMON-004", "인증이 필요합니다. 로그인 후 다시 시도해 주세요."),

    /*
     * 인증은 됐으나 권한이 없다. AccessDeniedException
     * API-7-05 에 따라 대상의 존재 여부를 드러내지 않는다. 없는 것과 권한이 없는 것이 같은 응답이어야 한다.
     *
     * COMMON-004 와 같은 이유로 지금 응답하는 자리는 없다. auth.md 가 AUTH-007 로 정했다.
     */
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "COMMON-005", "접근 권한이 없습니다."),

    /*
     * 매핑된 핸들러가 없는 경로다. NoResourceFoundException, NoHandlerFoundException
     * 리소스를 못 찾은 것이 아니므로 도메인의 ~_NOT_FOUND 자리에 이것을 쓰지 않는다.
     */
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-006", "요청하신 경로를 찾을 수 없습니다."),

    // 경로는 있으나 HTTP 메서드가 다르다 (HttpRequestMethodNotSupportedException)
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-007", "지원하지 않는 요청 방식입니다."),

    // 업로드 크기 상한 초과 (MaxUploadSizeExceededException)
    CONTENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "COMMON-008", "요청 크기가 허용 범위를 넘었습니다."),

    // Content-Type 을 처리할 수 없다 (HttpMediaTypeNotSupportedException)
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-009", "지원하지 않는 형식입니다. Content-Type 을 확인해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
