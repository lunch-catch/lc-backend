package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/*
 * 대상 클래스의 로거에 붙어 남긴 로그를 모아 두는 테스트 도우미다.
 * 로거 수준을 INFO 로 고정한다. 설정 파일이 없는 테스트에서는 루트가 DEBUG 라서
 * 수준에 따라 달라지는 동작이 환경에 따라 흔들린다.
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        this.originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    static LogCapture attach(Class<?> target) {
        return new LogCapture((Logger) LoggerFactory.getLogger(target));
    }

    void level(Level level) {
        logger.setLevel(level);
    }

    List<ILoggingEvent> events() {
        return appender.list;
    }

    ILoggingEvent only() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }
}
