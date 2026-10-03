package com.launchcatch.auth.exception;

import com.launchcatch.global.logging.AccessLogSignal;
import com.launchcatch.global.response.ResponseEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/*
 * 인증과 인가 실패를 auth.md 가 정한 코드로 바꾼다.
 *
 * ApiSecurityDefaults 가 필터에서 난 예외를 handlerExceptionResolver 로 MVC 예외 처리에
 * 되돌리므로, 필터에서 난 것과 메서드 보안에서 난 것이 모두 여기로 모인다.
 *
 * GlobalExceptionHandler 가 아니라 여기에 두는 이유는 의존 방향이다. 설계 문서 1.1절이
 * auth -> global 한 방향으로 정했고 global 은 아무것도 의존하지 않는다. global 에 두면
 * global -> auth 가 생겨 방향이 뒤집힌다. 옮기면서 global 이 Spring Security 를 아예
 * 모르게 되는 것은 덤이다.
 *
 * Order 를 가장 앞으로 둔다. GlobalExceptionHandler 는 Order 가 없어 가장 뒤이므로,
 * 같은 예외 타입을 둘이 다룰 때 이쪽이 이긴다. 지금 global 쪽에 그 둘은 없지만, 순서를
 * 값으로 적어 두지 않으면 나중에 누가 되살릴 때 어느 쪽이 이기는지가 운에 달린다.
 *
 * 범위를 패키지로 좁히지 않는다. 필터에서 난 예외는 handler 가 null 로 넘어와 패키지로
 * 좁힌 advice 가 선택되지 않는다. 그리고 이 둘은 경로와 무관하게 인증과 인가의 실패라
 * 전역에서 같은 코드로 답하는 것이 맞다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AuthExceptionHandler {

    /*
     * Access Token 이 없거나 유효하지 않거나 로그아웃 전에 발급된 경우다 (AUTH-005).
     * 클라이언트는 이 코드를 보고 재발급을 한 번 부른다 (api-spec/README.md 의 인증 절).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleAuthentication(
            AuthenticationException e, HttpServletRequest request) {
        return toResponse(AuthErrorCode.LOGIN_REQUIRED, request, "unauthenticated", e);
    }

    /*
     * 인증은 됐으나 역할이 맞지 않는 경우다 (AUTH-006).
     * 대상의 존재 여부를 드러내지 않도록 상세를 응답에 담지 않는다 (API-7-05).
     *
     * 점주 상태가 ONBOARDING 이라 막는 경우는 여기서 가리지 않는다. 이 모듈은 Role 만 알고
     * 점주 상태는 점주 도메인의 데이터다. 그 판정과 코드는 점주 도메인이 소유한다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleAccessDenied(
            AccessDeniedException e, HttpServletRequest request) {
        return toResponse(AuthErrorCode.ROLE_NOT_ALLOWED, request, "permission denied", e);
    }

    /*
     * 로그 등급은 ErrorCode 가 정한다. 여기서 코드마다 따로 적으면 enum 의 선언과 어긋난다.
     *
     * 예상된 답은 남기지 않고 접근 로그도 내린다. AUTH-005 이 그 경우다. Access 가 30분이라
     * 사용자마다 30분에 한 번은 이 응답을 받는데, 설계대로 도는 모습을 이상으로 남기면
     * 로그가 그 규모만큼 늘어난다. 세는 일은 지표가 한다.
     */
    private ResponseEntity<ResponseEnvelope<Void>> toResponse(
            AuthErrorCode errorCode, HttpServletRequest request, String what, Exception e) {
        if (errorCode.isExpectedTraffic()) {
            AccessLogSignal.markExpected(request);
            log.debug("{}. code={} detail={}", what, errorCode.getCode(), e.getMessage());
        } else {
            log.warn("{}. code={} detail={}", what, errorCode.getCode(), e.getMessage());
        }
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ResponseEnvelope.fail(errorCode));
    }
}
