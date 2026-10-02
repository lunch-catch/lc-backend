package com.launchcatch.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/*
 * 선언이 없으면 @CreatedDate 가 조용히 동작하지 않아 시각이 null 로 저장된다 (BE-3-03).
 * 이 선언은 애플리케이션 전체에서 한 번만 둔다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
