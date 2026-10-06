package com.launchcatch.ops.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 대기 정책을 본다. 실제 30초와 1분을 기다리면 아무도 이 검사를 돌리지 않으므로 값을 주입한다.
 */
class ThreadSleepRetryBackoffTest {

    private final ThreadSleepRetryBackoff backoff =
            new ThreadSleepRetryBackoff(List.of(Duration.ofMillis(1), Duration.ofMillis(2)));

    @Test
    @DisplayName("기다리면 true 를 돌려준다")
    void 기다린다() {
        assertThat(backoff.pause(1)).isTrue();
    }

    /*
     * 목록 길이를 넘는 시도는 마지막 값을 쓴다.
     * 상한은 행이 들고 있어서 여기까지 올 일이 없지만, 넘어오면 예외로 묶음을 깨는 것보다
     * 마지막 간격으로 기다리는 편이 낫다.
     */
    @Test
    @DisplayName("목록을 넘는 시도는 마지막 간격을 쓴다")
    void 마지막_간격을_쓴다() {
        assertThat(backoff.pause(5)).isTrue();
    }

    /** 중단 신호를 받으면 false 를 돌려주고 중단 상태를 되살린다. */
    @Test
    @DisplayName("중단되면 false 를 돌려주고 중단 상태를 남긴다")
    void 중단된다() {
        Thread.currentThread().interrupt();

        assertThat(backoff.pause(1)).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }
}
