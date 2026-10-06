package com.launchcatch.ops.scheduler;

import java.net.SocketTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

/*
 * 다시 돌리면 될 수도 있는 실패인지 가른다 (배치 운영 문서 2장의 자동 재실행).
 *
 * 가르는 기준은 "원인이 사라질 수 있는가" 다. 교착과 락 대기 시간 초과, 연결 끊김, 응답 시간
 * 초과는 같은 입력으로 다시 돌리면 될 수 있다. 반대로 데이터가 어긋났거나 제약을 위반한 실패는
 * 몇 번 돌려도 같은 자리에서 터지고, 다시 돌리면 관리자가 볼 기록만 덮어쓴다.
 *
 * 드라이버 예외 타입에 직접 의존하지 않는다. Spring 이 번역한 계층으로 본다.
 * TransientDataAccessException 아래에 교착(DeadlockLoserDataAccessException),
 * 락 대기 시간 초과(CannotAcquireLockException), 응답 시간 초과(QueryTimeoutException)가 있다.
 * 연결 실패는 그 가지가 아니라 DataAccessResourceFailureException 이라 따로 본다.
 *
 * 원인 사슬도 훑는다. Lettuce 의 RedisCommandTimeoutException 처럼 Spring 계층 밖에서
 * 올라오는 것이 있어서, 이름에 Timeout 이 든 것과 SocketTimeoutException 을 함께 받는다.
 * auth 의 RedisFailureClassifier 가 같은 방식을 쓰는데, 그쪽은 타임아웃만 가리고 auth 소유라
 * 여기서 가져다 쓰지 않는다.
 */
public final class TransientFailures {

    private TransientFailures() {
    }

    public static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (matches(cause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Throwable cause) {
        return cause instanceof TransientDataAccessException
                || cause instanceof DataAccessResourceFailureException
                || cause instanceof SocketTimeoutException
                || cause.getClass().getSimpleName().contains("Timeout");
    }
}
