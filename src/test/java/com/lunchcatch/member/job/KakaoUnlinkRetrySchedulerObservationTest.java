package com.lunchcatch.member.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lunchcatch.global.logging.SchedulerLoggingAspect;
import com.lunchcatch.member.service.KakaoUnlinkRetryService;
import com.lunchcatch.ops.service.BatchExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class KakaoUnlinkRetrySchedulerObservationTest {

    private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);
    private final KakaoUnlinkRetryService retry = mock(KakaoUnlinkRetryService.class);
    private final BatchExecutionService executions = mock(BatchExecutionService.class);
    private final Clock clock = mock(Clock.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SchedulerLoggingAspect.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level originalLevel;
    private KakaoUnlinkRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
        when(clock.instant()).thenReturn(START);
        when(executions.businessDate()).thenReturn(BUSINESS_DATE);
        when(executions.claim(KakaoUnlinkRetryScheduler.JOB_NAME, BUSINESS_DATE)).thenReturn(true);
        AspectJProxyFactory factory = new AspectJProxyFactory(new KakaoUnlinkRetryScheduler(retry, executions));
        factory.addAspect(new SchedulerLoggingAspect(registry, clock));
        // StandaloneJob 을 구현해 인터페이스 프록시가 기본이 된다. 운영의 Spring Boot 처럼 클래스 프록시로 만든다
        factory.setProxyTargetClass(true);
        scheduler = factory.getProxy();
    }

    @AfterEach
    void close() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
        registry.close();
    }

    @Test
    void 배치_실패는_DB와_공통_로그에_반영하고_마지막_성공_시각을_유지한다() {
        scheduler.retryPendingUnlinks();
        logs.list.clear();
        when(clock.instant()).thenReturn(START.plusSeconds(60));
        IllegalStateException failure = new IllegalStateException("재시도 실패");
        doThrow(failure).when(retry).retryPending();

        assertThatThrownBy(() -> scheduler.retryPendingUnlinks()).isSameAs(failure);

        verify(executions).fail(eq(KakaoUnlinkRetryScheduler.JOB_NAME), eq(BUSINESS_DATE), anyString());
        assertThat(registry.get("batch.job.last.success.timestamp").gauge().value()).isEqualTo(START.getEpochSecond());
        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.INFO, Level.ERROR);
        assertThat(logs.list.get(1).getFormattedMessage()).contains("event=SCHEDULER_FAILED").doesNotContain("SCHEDULER_END");
        assertThat(logs.list.get(1).getThrowableProxy()).isNotNull();
    }

    @Test
    void 정상_배치는_성공으로_닫고_성공_시각을_갱신한다() {
        scheduler.retryPendingUnlinks();
        when(clock.instant()).thenReturn(START.plusSeconds(60));

        scheduler.retryPendingUnlinks();

        assertThat(registry.get("batch.job.last.success.timestamp").gauge().value()).isEqualTo(START.plusSeconds(60).getEpochSecond());
        assertThat(logs.list.get(3).getFormattedMessage()).contains("event=SCHEDULER_END");
    }
}
