package com.lunchcatch.global.logging;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.web.reactive.function.client.WebClientResponseException;

// 예외 메시지와 응답 본문을 제외하고 장애 원인과 발생 위치만 로그에 전달한다.
public record FailureLogDetails(
        List<String> causeTypes,
        Integer httpStatus,
        String sqlState,
        Integer sqlErrorCode,
        String origin) {

    public static FailureLogDetails from(Throwable failure) {
        List<String> types = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Integer status = null;
        String state = null;
        Integer errorCode = null;
        Throwable root = failure;
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            types.add(cause.getClass().getSimpleName());
            root = cause;
            if (cause instanceof WebClientResponseException response) {
                status = response.getStatusCode().value();
            }
            if (cause instanceof SQLException sql) {
                String candidate = sql.getSQLState();
                state = candidate != null && candidate.matches("[A-Z0-9]{5}") ? candidate : null;
                errorCode = sql.getErrorCode();
            }
        }
        String location = root != null && root.getStackTrace().length > 0
                ? root.getStackTrace()[0].toString() : null;
        return new FailureLogDetails(List.copyOf(types), status, state, errorCode, location);
    }
}
