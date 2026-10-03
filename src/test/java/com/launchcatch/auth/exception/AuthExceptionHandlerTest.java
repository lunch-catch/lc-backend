package com.launchcatch.auth.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.global.logging.AccessLogSignal;
import com.launchcatch.global.response.ResponseEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class AuthExceptionHandlerTest {

    private final AuthExceptionHandler handler = new AuthExceptionHandler();

    @Test
    @DisplayName("인증 실패는 401 과 AUTH-005 으로 답한다")
    void 인증_실패() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ResponseEnvelope<Void>> response =
                handler.handleAuthentication(new BadCredentialsException("no token"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("AUTH-005");
        assertThat(response.getBody().message()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED.getMessage());
        assertThat(response.getBody().data()).isNull();
    }

    /*
     * Access 가 30분이라 사용자마다 30분에 한 번은 이 응답을 받는다.
     * 설계대로 도는 모습이라 접근 로그를 내린다. 이 표시가 빠지면 로그가 그 규모만큼 늘어난다.
     */
    @Test
    @DisplayName("인증 실패는 접근 로그를 예상된 것으로 표시한다")
    void 인증_실패는_접근_로그를_내린다() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.handleAuthentication(new BadCredentialsException("no token"), request);

        assertThat(AccessLogSignal.isExpected(request)).isTrue();
    }

    @Test
    @DisplayName("인가 실패는 403 과 AUTH-006 으로 답한다")
    void 인가_실패() {
        ResponseEntity<ResponseEnvelope<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("role mismatch"), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("AUTH-006");
        assertThat(response.getBody().message()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED.getMessage());
        assertThat(response.getBody().data()).isNull();
    }

    /*
     * 인가 실패는 예상된 답이 아니다. 역할이 안 맞는 호출은 정상 흐름이 아니라 남겨야 한다.
     * enum 의 isExpectedTraffic 과 핸들러의 동작이 어긋나지 않는지 본다.
     */
    @Test
    @DisplayName("인가 실패는 접근 로그를 내리지 않는다")
    void 인가_실패는_접근_로그를_내리지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.handleAccessDenied(new AccessDeniedException("role mismatch"), request);

        assertThat(AccessLogSignal.isExpected(request)).isFalse();
    }

    /*
     * 대상의 존재 여부를 드러내지 않는다 (API-7-05).
     * 예외 메시지를 응답에 실으면 어느 가게나 캠페인이 있는지가 응답으로 새어 나간다.
     */
    @Test
    @DisplayName("인가 실패 응답에 예외 메시지를 담지 않는다")
    void 인가_실패는_상세를_담지_않는다() {
        ResponseEntity<ResponseEnvelope<Void>> response =
                handler.handleAccessDenied(
                        new AccessDeniedException("campaign 42 belongs to owner 7"),
                        new MockHttpServletRequest());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain("campaign", "42", "owner", "7");
    }
}
