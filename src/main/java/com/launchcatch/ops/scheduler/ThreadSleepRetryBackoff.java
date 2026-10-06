package com.launchcatch.ops.scheduler;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
 * 스케줄러 스레드에서 그대로 기다린다.
 *
 * 00:00 묶음은 "실패한 단계만 다시 실행한다" 가 규칙이다. 그 자리에서 기다리면 단계가 바뀌지
 * 않아 그 규칙이 저절로 지켜진다. 별도 패스로 빼면 어느 단계까지 끝났는지를 다시 읽어야 한다.
 *
 * 묶음을 들고 있는 동안 생존 신호는 다른 스레드에서 돈다. 그래서 최대 90초를 기다려도 다른
 * 서버가 빼앗지 않는다. 그것이 스케줄러 스레드 풀을 1보다 크게 둔 이유다.
 */
@Slf4j
@Component
public class ThreadSleepRetryBackoff implements RetryBackoff {

    /** 첫 재실행은 30초 뒤, 두 번째는 1분 뒤다. 목록 길이가 곧 최대 재실행 횟수다. */
    private final List<Duration> waits;

    public ThreadSleepRetryBackoff(
            @Value("${launchcatch.batch.retry-waits:30s,1m}") List<Duration> waits) {
        this.waits = List.copyOf(waits);
    }

    @Override
    public boolean pause(int attempt) {
        Duration wait = waits.get(Math.min(attempt, waits.size()) - 1);
        log.info("일시적 오류로 {}초 뒤에 다시 실행한다. 시도={}", wait.toSeconds(), attempt);
        try {
            Thread.sleep(wait.toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
