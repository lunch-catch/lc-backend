package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.metrics.tracer.common.SpanContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ExemplarConfigTest {

    // 운영과 로컬처럼 에이전트를 붙이지 않은 앱은 지표가 지금과 같아야 한다. 테스트 JVM 에는 에이전트가 없다
    @Test
    @DisplayName("OTel 에이전트가 없으면 exemplar 를 붙이는 빈을 만들지 않는다")
    void 에이전트가_없으면_빈을_만들지_않는다() {
        new ApplicationContextRunner()
                .withUserConfiguration(ExemplarConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean(SpanContext.class));
    }
}
