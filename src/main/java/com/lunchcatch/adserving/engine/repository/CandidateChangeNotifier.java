package com.lunchcatch.adserving.engine.repository;

import com.lunchcatch.adserving.engine.CandidateChange;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/*
 * 그날 후보가 바뀌었다고 Valkey pub/sub 로 모든 서버에 알린다(#1042 결정 1). 메시지 형식은 CandidateChange 가 갖는다.
 * 트랜잭션 안이면 커밋된 뒤에 보낸다. 커밋 전에 보내면 다른 서버가 바뀌기 전 값을 다시 읽는다.
 * 보내지 못해도 예외를 올리지 않고 센다. 받는 쪽이 5초마다 다시 읽어 맞춰지므로 후보를 바꾼 쪽을 실패시키지 않는다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CandidateChangeNotifier {

    static final String PUBLISH_FAILED_METRIC = "adserving_candidate_change_publish_failed_total";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    // 후보 하나(소진, 상태)가 바뀌었다.
    public void changed(LocalDate businessDate, Long campaignId) {
        send(CandidateChange.one(businessDate, campaignId).message());
    }

    // 그날 후보가 통째로 바뀌었다(적재, 예산).
    public void reloaded(LocalDate businessDate) {
        send(CandidateChange.wholeDay(businessDate).message());
    }

    private void send(String message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(message);
            }
        });
    }

    private void publish(String message) {
        try {
            redisTemplate.convertAndSend(CandidateChange.CHANNEL, message);
        } catch (DataAccessException e) {
            meterRegistry.counter(PUBLISH_FAILED_METRIC).increment();
            log.warn("event=ADSERVING_CANDIDATE_CHANGE_PUBLISH_FAILED change={} fallback=RELOAD_WITHIN_5S", message, e);
        }
    }
}
