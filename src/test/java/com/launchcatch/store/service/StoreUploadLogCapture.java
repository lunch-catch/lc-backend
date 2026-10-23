package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;

// 업로드 로그의 이벤트·레벨·식별자와 민감정보 제외를 검증하고 로거 설정을 원래대로 돌린다.
final class StoreUploadLogCapture implements AutoCloseable {
    private final Map<Logger, Level> originalLevels = new LinkedHashMap<>();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private StoreUploadLogCapture(Class<?>... targets) {
        appender.start();
        for (Class<?> target : targets) {
            Logger logger = (Logger) LoggerFactory.getLogger(target);
            originalLevels.put(logger, logger.getLevel());
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }
    }

    static StoreUploadLogCapture attach(Class<?>... targets) {
        return new StoreUploadLogCapture(targets);
    }

    void assertEvent(String eventName, Level level, String... fields) {
        var matches = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("event=" + eventName + " "))
                .toList();
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().getLevel()).isEqualTo(level);
        assertThat(matches.getFirst().getFormattedMessage()).contains(fields);
    }

    void assertNoSensitiveData(String... values) {
        assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(values);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Override
    public void close() {
        originalLevels.forEach((logger, level) -> {
            logger.detachAppender(appender);
            logger.setLevel(level);
        });
        appender.stop();
    }
}
