package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/*
 * 앱과 배치의 DB 시간 제한이 층마다 고른 값인지 본다(#1217).
 * 쿼리 시간 제한이 실제 상한이고 socketTimeout 은 그보다 긴 안전망이라, 두 값의 순서도 함께 본다.
 */
class DatabaseTimeoutSettingsTest {

    private static final String SOCKET_TIMEOUT = "spring.datasource.hikari.data-source-properties.socketTimeout";
    private static final String JPA_QUERY_TIMEOUT = "spring.jpa.properties.jakarta.persistence.query.timeout";
    private static final String JDBC_QUERY_TIMEOUT = "spring.jdbc.template.query-timeout";
    private static final String TRANSACTION_TIMEOUT = "spring.transaction.default-timeout";

    @Test
    @DisplayName("앱은 쿼리와 트랜잭션을 5초에 끊고 socketTimeout 은 10초다")
    void 앱의_시간_제한() {
        ConfigurableEnvironment app = resolved();

        assertThat(app.getProperty(JPA_QUERY_TIMEOUT)).isEqualTo("5000");
        assertThat(app.getProperty(JDBC_QUERY_TIMEOUT)).isEqualTo("5s");
        assertThat(app.getProperty(TRANSACTION_TIMEOUT)).isEqualTo("5s");
        assertThat(app.getProperty(SOCKET_TIMEOUT)).isEqualTo("10000");
    }

    // 00:00 묶음을 트랜잭션으로 감싸지 않아 트랜잭션 시간 제한은 걸 자리가 없다
    @Test
    @DisplayName("batch 프로필은 쿼리를 2분에 끊고 socketTimeout 은 5분이며 트랜잭션 시간 제한은 없다")
    void 배치의_시간_제한() {
        ConfigurableEnvironment batch = resolved("batch");

        assertThat(batch.getProperty(JPA_QUERY_TIMEOUT)).isEqualTo("120000");
        assertThat(batch.getProperty(JDBC_QUERY_TIMEOUT)).isEqualTo("2m");
        assertThat(batch.getProperty(TRANSACTION_TIMEOUT)).isEqualTo("-1s");
        assertThat(batch.getProperty(SOCKET_TIMEOUT)).isEqualTo("300000");
    }

    // 쿼리 시간 제한이 먼저 걸려야 서버 쪽 쿼리까지 멈춘다. socketTimeout 이 먼저면 기다리기만 그만두고 쿼리는 계속 돈다
    @Test
    @DisplayName("앱과 배치 모두 쿼리 시간 제한이 socketTimeout 보다 짧다")
    void 쿼리_시간_제한이_socketTimeout_보다_짧다() {
        for (ConfigurableEnvironment environment : new ConfigurableEnvironment[] {resolved(), resolved("batch")}) {
            assertThat(Long.parseLong(environment.getProperty(JPA_QUERY_TIMEOUT)))
                    .isLessThan(Long.parseLong(environment.getProperty(SOCKET_TIMEOUT)));
        }
    }

    // 앱이 뜰 때와 같은 순서로 application.yml 과 프로필 파일의 모든 문서를 읽어 겹친 결과다
    private static ConfigurableEnvironment resolved(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(),
                new DefaultBootstrapContext(), profiles);
        return environment;
    }
}
