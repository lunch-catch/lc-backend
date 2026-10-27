package com.lunchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class CouponFailureDetailsTest {
    @Test
    void SQL_원인은_메시지와_스택_없이_진단_필드로_반환한다() {
        var failure = new DataIntegrityViolationException("secret-token",
                new SQLException("secret-value", "23000", 1062));

        CouponFailureDetails details = CouponFailureDetails.from(failure);

        assertThat(details.exceptionType()).isEqualTo("DataIntegrityViolationException>SQLException");
        assertThat(details.sqlState()).isEqualTo("23000");
        assertThat(details.sqlErrorCode()).isEqualTo(1062);
        assertThat(details.origin()).isNotBlank().doesNotContain("secret");
        assertThat(details.stackTrace()).isNull();
    }

    @ParameterizedTest
    @MethodSource("sensitiveFailures")
    void 외부_예외가_원인이나_억제된_예외에_있어도_스택에서_제외한다(Throwable sensitive) {
        var caused = new IllegalStateException("wrapper", sensitive);
        var suppressed = new IllegalStateException("wrapper");
        suppressed.addSuppressed(sensitive);

        assertThat(CouponFailureDetails.from(caused).stackTrace()).isNull();
        assertThat(CouponFailureDetails.from(suppressed).stackTrace()).isNull();
    }

    @Test
    void 민감한_예외가_없으면_순환하는_원인도_끝내고_원래_스택을_유지한다() {
        var failure = new IllegalStateException("first");
        var cause = new IllegalArgumentException("second", failure);
        failure.initCause(cause);
        failure.addSuppressed(cause);

        assertThat(CouponFailureDetails.from(failure).stackTrace()).isSameAs(failure);
    }

    @Test
    void Redis_실패는_예외_메시지_없이_타임아웃과_그_외를_구분한다() {
        assertThat(CouponFailureDetails.reason(new QueryTimeoutException("secret"))).isEqualTo("TIMEOUT");
        assertThat(CouponFailureDetails.reason(new DataAccessResourceFailureException("secret", new SocketTimeoutException())))
                .isEqualTo("TIMEOUT");
        assertThat(CouponFailureDetails.reason(new IllegalStateException("secret"))).isEqualTo("OTHER");
        assertThat(CouponFailureDetails.reason(new DataAccessResourceFailureException("secret"))).isEqualTo("OTHER");
    }

    private static Stream<Throwable> sensitiveFailures() {
        return Stream.of(new SQLException("secret"), new DataIntegrityViolationException("secret"),
                WebClientResponseException.create(400, "secret", null, null, null),
                new io.jsonwebtoken.JwtException("secret"),
                new org.springframework.security.oauth2.jwt.JwtException("secret"));
    }
}
