package com.launchcatch.ops.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;

/*
 * 다시 돌려도 되는 실패와 그렇지 않은 실패를 가르는 선을 고정한다.
 *
 * 선이 넓으면 데이터가 어긋난 실패를 세 번 돌려 관리자가 볼 기록을 덮어쓴다. 좁으면 교착 한 번에
 * 그날 집계 전체가 멈춰 사람이 새벽에 깨야 한다. 어느 쪽도 조용히 바뀌면 안 된다.
 */
class TransientFailuresTest {

    /** Lettuce 의 RedisCommandTimeoutException 처럼 Spring 계층 밖에서 올라오는 것을 흉내 낸다. */
    private static class SomethingTimeoutException extends RuntimeException {
        SomethingTimeoutException() {
            super("응답이 없다");
        }
    }

    @Test
    @DisplayName("교착은 다시 돌린다")
    void 교착() {
        assertThat(TransientFailures.isTransient(
                new DeadlockLoserDataAccessException("1213", null))).isTrue();
    }

    @Test
    @DisplayName("락 대기 시간 초과는 다시 돌린다")
    void 락_대기_시간_초과() {
        assertThat(TransientFailures.isTransient(new CannotAcquireLockException("1205"))).isTrue();
    }

    @Test
    @DisplayName("연결 실패는 다시 돌린다")
    void 연결_실패() {
        assertThat(TransientFailures.isTransient(
                new DataAccessResourceFailureException("연결이 끊겼다"))).isTrue();
    }

    @Test
    @DisplayName("응답 시간 초과는 다시 돌린다")
    void 응답_시간_초과() {
        assertThat(TransientFailures.isTransient(new QueryTimeoutException("시간 초과"))).isTrue();
    }

    /*
     * 원인 사슬을 훑는다.
     * 작업 코드가 자기 예외로 감싸 던지면 겉은 일시적 오류가 아니다.
     */
    @Test
    @DisplayName("감싸인 소켓 시간 초과도 다시 돌린다")
    void 감싸인_소켓_시간_초과() {
        assertThat(TransientFailures.isTransient(
                new IllegalStateException("집계 실패", new SocketTimeoutException()))).isTrue();
    }

    @Test
    @DisplayName("이름에 Timeout 이 든 예외도 다시 돌린다")
    void 이름으로_판정한다() {
        assertThat(TransientFailures.isTransient(new SomethingTimeoutException())).isTrue();
    }

    /*
     * 제약 위반은 다시 돌려도 같은 자리에서 터진다.
     * TransientDataAccessException 가 아니라 NonTransient 쪽이라 걸리지 않아야 한다.
     */
    @Test
    @DisplayName("제약 위반은 다시 돌리지 않는다")
    void 제약_위반() {
        assertThat(TransientFailures.isTransient(
                new DataIntegrityViolationException("중복"))).isFalse();
    }

    @Test
    @DisplayName("업무 오류는 다시 돌리지 않는다")
    void 업무_오류() {
        assertThat(TransientFailures.isTransient(
                new IllegalStateException("원본이 어긋났다"))).isFalse();
    }
}
