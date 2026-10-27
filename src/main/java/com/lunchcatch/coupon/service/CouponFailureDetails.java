package com.lunchcatch.coupon.service;

import com.lunchcatch.auth.RedisFailureClassifier;
import com.lunchcatch.global.logging.FailureLogDetails;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

// 민감한 외부 예외는 메시지를 출력하지 않고 진단 필드로 남긴다.
record CouponFailureDetails(String exceptionType, Integer httpStatus, String sqlState,
        Integer sqlErrorCode, String origin, Throwable stackTrace) {

    static CouponFailureDetails from(Throwable failure) {
        FailureLogDetails details = FailureLogDetails.from(failure);
        return new CouponFailureDetails(String.join(">", details.causeTypes()), details.httpStatus(),
                details.sqlState(), details.sqlErrorCode(), details.origin(), safeStackTrace(failure));
    }

    static String reason(Throwable failure) {
        return failure instanceof DataAccessException dataAccess
                ? RedisFailureClassifier.causeLabel(dataAccess) : "OTHER";
    }

    private static Throwable safeStackTrace(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException || current instanceof DataIntegrityViolationException
                    || current instanceof WebClientResponseException
                    || current instanceof io.jsonwebtoken.JwtException
                    || current instanceof org.springframework.security.oauth2.jwt.JwtException) {
                return null;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return failure;
    }
}
