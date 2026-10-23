package com.launchcatch.analytics.batch.service;

import com.launchcatch.analytics.batch.repository.EventLogRetentionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 보존 기간(13개월)이 지난 이벤트 로그를 지운다(I-03).
 *
 * 삭제 선은 일별 집계가 다시 돌 수 있는 가장 오래된 영업일(DailyAggregationRunner.oldestRetained)의
 * 00:00 이다. 그 날짜 이후의 이벤트는 남으므로 재집계할 수 있는 날짜의 원본은 지워지지 않는다.
 *
 * 이 메서드는 트랜잭션을 걸지 않는다. 저장소의 청크마다 자기 트랜잭션으로 커밋돼, 쌓인 양이 많아도
 * 한 트랜잭션이 락을 오래 들고 있지 않는다. 묶어서 커밋하면 청크로 나눈 의미가 없어진다.
 *
 * DB 쓰기는 실패하면 되돌리는 것이 기본이지만 여기서는 벗어난다. 중간 청크에서 실패해도 앞 청크의
 * 삭제는 되돌리지 않는다. 지운 행은 어차피 지워질 행이라 부분 반영이 어긋남을 만들지 않고, 남은 행은
 * 다음 실행이 이어서 지운다.
 */
@Slf4j
@Service
public class EventLogRetentionService {

    static final int BATCH_SIZE = 1000;

    // 청크 사이에 쉬는 시간이다. 이벤트 기록 쓰기와 락을 다투는 시간을 줄인다.
    static final long PAUSE_MILLIS = 100;

    private final EventLogRetentionRepository repository;
    private final Clock clock;

    public EventLogRetentionService(EventLogRetentionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /*
     * 지운 행 수를 돌려준다.
     * 중단 신호를 받으면 예외를 던진다. 덜 지운 실행을 성공으로 닫지 않고 호출자가 실패로 닫게 한다.
     * 남은 행은 다음 실행이 이어서 지운다.
     */
    public int purge() {
        LocalDateTime cutoff = cutoff(LocalDate.now(clock));
        int total = 0;
        while (true) {
            int deleted = repository.deleteOlderThan(cutoff, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
            pause();
        }
        log.info("event=ANALYTICS_EVENT_LOG_RETENTION_DONE cutoff={} deletedCount={}", cutoff, total);
        return total;
    }

    // 이 시각보다 이전에 일어난 이벤트가 삭제 대상이다.
    LocalDateTime cutoff(LocalDate today) {
        return today.minusMonths(DailyAggregationRunner.RETENTION_MONTHS).atStartOfDay();
    }

    private void pause() {
        try {
            Thread.sleep(PAUSE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Event log retention interrupted", interrupted);
        }
    }
}
