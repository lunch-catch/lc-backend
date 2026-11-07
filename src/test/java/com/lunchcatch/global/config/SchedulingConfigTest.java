package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConfigTest {

    @Test
    void 스케줄러의_오류_처리기는_실패를_다시_로그로_남기지_않는다() throws InterruptedException {
        Logger spring = (Logger) LoggerFactory.getLogger("org.springframework.scheduling.support.TaskUtils$LoggingErrorHandler");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        spring.addAppender(appender);
        spring.setLevel(Level.DEBUG);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        new SchedulingConfig().schedulerErrorHandler().customize(scheduler);
        scheduler.initialize();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            scheduler.schedule(() -> {
                ran.countDown();
                throw new IllegalStateException("boom");
            }, Instant.now());

            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.shutdown();
            scheduler.getScheduledExecutor().awaitTermination(5, TimeUnit.SECONDS);

            assertThat(appender.list).isEmpty();
        } finally {
            spring.detachAppender(appender);
            spring.setLevel(null);
        }
    }
}
