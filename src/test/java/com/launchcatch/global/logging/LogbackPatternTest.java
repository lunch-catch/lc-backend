package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogbackPatternTest {

    @Test
    void MDC에_키가_없으면_기본값을_출력한다() {
        // given
        PatternLayout layout = layout("%X{traceId:-missing} - %msg");

        // when
        String rendered = layout.doLayout(event("hello"));

        // then
        assertThat(rendered).isEqualTo("missing - hello");
    }

    @Test
    void 변환자_뒤의_리터럴_괄호를_이스케이프하면_뒤의_메시지도_출력한다() {
        // given
        PatternLayout layout = layout("%logger{36}\\(%F:%line\\) - %msg");

        // when
        String rendered = layout.doLayout(event("message-after-parentheses"));

        // then
        assertThat(rendered).contains("(Example.java:42) - message-after-parentheses");
    }

    @Test
    void 변환자_뒤의_리터럴_괄호를_이스케이프하지_않으면_뒤의_메시지가_출력되지_않는다() {
        // given
        PatternLayout layout = layout("%logger{36}(%F:%line) - %msg");

        // when
        String rendered = layout.doLayout(event("message-after-parentheses"));

        // then
        assertThat(rendered).doesNotContain("message-after-parentheses");
    }

    private PatternLayout layout(String pattern) {
        PatternLayout layout = new PatternLayout();
        layout.setContext(new LoggerContext());
        layout.setPattern(pattern);
        layout.start();
        return layout;
    }

    private LoggingEvent event(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("com.launchcatch.Example");
        event.setMessage(message);
        event.setCallerData(new StackTraceElement[]{
                new StackTraceElement("com.launchcatch.Example", "run", "Example.java", 42)
        });
        event.setMDCPropertyMap(Map.of());
        return event;
    }
}
