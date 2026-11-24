package com.lunchcatch.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;

class StoreFailuresTest {

    @Test
    void 커넥션과_자원_장애와_잠금_대기와_시간_초과는_저장소_장애다() {
        assertThat(StoreFailures.toResponse(new CannotCreateTransactionException("pool exhausted")))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE));
        assertThat(StoreFailures.toResponse(new DataAccessResourceFailureException("db down")))
                .isInstanceOf(AuthException.class);
        assertThat(StoreFailures.toResponse(new CannotAcquireLockException("lock wait")))
                .isInstanceOf(AuthException.class);
        assertThat(StoreFailures.toResponse(new QueryTimeoutException("timeout")))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 저장소_장애로_바꿀_때_원래_예외를_원인으로_남긴다() {
        CannotCreateTransactionException failure = new CannotCreateTransactionException("pool exhausted");

        assertThat(StoreFailures.toResponse(failure)).hasCause(failure);
    }

    @Test
    void 코드_버그는_바꾸지_않고_원래_예외를_돌려준다() {
        BadSqlGrammarException badSql = new BadSqlGrammarException("rotate", "UPDATE", new SQLException("syntax"));
        DataIntegrityViolationException constraint = new DataIntegrityViolationException("constraint");
        IllegalTransactionStateException propagation = new IllegalTransactionStateException("no transaction");

        assertThat(StoreFailures.toResponse(badSql)).isSameAs(badSql);
        assertThat(StoreFailures.toResponse(constraint)).isSameAs(constraint);
        assertThat(StoreFailures.toResponse(propagation)).isSameAs(propagation);
    }

    @Test
    void 이미_저장소_장애로_바꾼_예외는_그대로_돌려준다() {
        AuthException unavailable = new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);

        assertThat(StoreFailures.toResponse(unavailable)).isSameAs(unavailable);
    }
}
