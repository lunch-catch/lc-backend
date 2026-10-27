package com.lunchcatch.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class AnalyticsFraudPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withConfiguration(UserConfigurations.of(AnalyticsFraudConfig.class));

    @Test
    void 값을_주지_않으면_정한_기본값이다() {
        runner.run(context -> {
            AnalyticsFraudProperties properties = context.getBean(AnalyticsFraudProperties.class);
            assertThat(properties.topMembers()).isEqualTo(5);
            assertThat(properties.concentrationShare()).isEqualTo(0.5);
            assertThat(properties.minimumImpressions()).isEqualTo(100);
            assertThat(properties.minimumInvalidCount()).isEqualTo(100);
        });
    }

    @Test
    void 설정으로_값을_바꿀_수_있다() {
        runner.withPropertyValues(
                        "analytics.fraud.top-members=3",
                        "analytics.fraud.concentration-share=0.7",
                        "analytics.fraud.minimum-impressions=50",
                        "analytics.fraud.minimum-invalid-count=30")
                .run(context -> {
                    AnalyticsFraudProperties properties = context.getBean(AnalyticsFraudProperties.class);
                    assertThat(properties).isEqualTo(new AnalyticsFraudProperties(3, 0.7, 50, 30));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "analytics.fraud.top-members=0",
            "analytics.fraud.top-members=101",
            "analytics.fraud.concentration-share=0",
            "analytics.fraud.concentration-share=1.1",
            "analytics.fraud.minimum-impressions=0",
            "analytics.fraud.minimum-invalid-count=0"
    })
    void 범위를_벗어난_값이면_기동이_실패한다(String property) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 점유율_기준_1은_허용한다() {
        runner.withPropertyValues("analytics.fraud.concentration-share=1.0")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
