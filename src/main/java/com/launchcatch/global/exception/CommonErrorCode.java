package com.launchcatch.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 특정 도메인에 속하지 않는 공통 오류 코드.
 * 도메인 지식이 필요 없는 프레임워크 경계의 실패만 둔다.
 * 회원을 못 찾았다거나 선착순 수량이 모자란다는 것은 도메인이 아는 실패라 각 도메인의 ErrorCode 로 간다.
 *
 * 인증과 인가의 실패는 여기 두지 않는다. 그 실패를 만드는 조건이 토큰 수명과 회전, 로그아웃
 * 커트라인이라 인증 모듈이 소유하고, 응답의 성격도 그 정책에서 나온다. 예를 들어 로그인이
 * 필요하다는 답을 로그로 남기지 않는 근거는 Access 가 30분이라는 값인데 이 계층은 그것을
 * 모른다. auth.exception.AuthErrorCode 가 AUTH-006 과 AUTH-007 로 답한다.
 *
 * 거꾸로 요청 검증 실패는 여기 둔다. 판정이 전부 요청 타입의 애노테이션에서 나오고 위
 * 계층이 참여하는 부분이 없다. 경로마다 코드를 나누면 같은 실패에 다른 코드가 가고
 * 클라이언트가 할 일은 그대로다.
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
     * 매핑된 핸들러가 없는 경로다. NoResourceFoundException, NoHandlerFoundException
     * 리소스를 못 찾은 것이 아니므로 도메인의 ~_NOT_FOUND 자리에 이것을 쓰지 않는다.
     */
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-004", "요청하신 경로를 찾을 수 없습니다."),

    // 경로는 있으나 HTTP 메서드가 다르다 (HttpRequestMethodNotSupportedException)
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-005", "지원하지 않는 요청 방식입니다."),

    // 업로드 크기 상한 초과 (MaxUploadSizeExceededException)
    CONTENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "COMMON-006", "요청 크기가 허용 범위를 넘었습니다."),

    // Content-Type 을 처리할 수 없다 (HttpMediaTypeNotSupportedException)
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-007", "지원하지 않는 형식입니다. Content-Type 을 확인해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
