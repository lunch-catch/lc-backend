package com.launchcatch.global.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/*
 * 선언이 없으면 @CreatedDate 가 조용히 동작하지 않아 시각이 null 로 저장된다 (BE-3-03).
 * 이 선언은 애플리케이션 전체에서 한 번만 둔다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /*
     * 감사 시각도 ClockConfig 의 Clock 을 쓴다.
     * 기본 제공자는 JVM 기본 시간대로 LocalDateTime.now() 를 부른다. 컨테이너가 UTC 로 뜨면
     * created_at 만 아홉 시간 어긋나고, 애플리케이션이 Clock 으로 넣는 finished_at 과 기준이
     * 갈린다. batch_execution_log 의 chk_batch_finished 처럼 두 시각을 견주는 제약이
     * 그 차이 위에서 판정된다.
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
