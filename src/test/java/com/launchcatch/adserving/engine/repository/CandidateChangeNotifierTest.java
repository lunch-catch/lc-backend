package com.launchcatch.adserving.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.CandidateChange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("후보 변경 알림")
class CandidateChangeNotifierTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CandidateChangeNotifier notifier = new CandidateChangeNotifier(redisTemplate, meterRegistry);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // 다른 서버가 커밋 전에 다시 읽으면 바뀌기 전 값을 읽는다
    @Test
    void 트랜잭션_안이면_커밋된_뒤에_알린다() {
        TransactionSynchronizationManager.initSynchronization();

        notifier.changed(DATE, 101L);

        verifyNoInteractions(redisTemplate);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(redisTemplate).convertAndSend(CandidateChange.CHANNEL, "2026-10-01:101");
    }

    @Test
    void 트랜잭션_밖이면_바로_알리고_그날_전체는_별표로_보낸다() {
        notifier.reloaded(DATE);

        verify(redisTemplate).convertAndSend(CandidateChange.CHANNEL, "2026-10-01:*");
    }

    // 알림을 놓쳐도 5초 안에 다시 읽어 맞춰진다. 후보를 바꾼 쪽을 실패시키지 않는다
    @Test
    void Valkey_가_멈추면_알리지_못한_것을_세고_예외를_올리지_않는다() {
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenThrow(new RedisConnectionFailureException("멈췄다"));

        assertThatCode(() -> notifier.changed(DATE, 101L)).doesNotThrowAnyException();

        assertThat(meterRegistry.counter(CandidateChangeNotifier.PUBLISH_FAILED_METRIC).count()).isEqualTo(1);
    }

    @Test
    void 받은_알림을_날과_후보로_푼다() {
        assertThat(CandidateChange.parse("2026-10-01:101")).isEqualTo(CandidateChange.one(DATE, 101L));
        assertThat(CandidateChange.parse("2026-10-01:*")).isEqualTo(CandidateChange.wholeDay(DATE));
    }
}
