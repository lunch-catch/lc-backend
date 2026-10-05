package com.launchcatch.global.config;

import javax.sql.DataSource;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * 도메인별 마이그레이션을 엔티티 매니저보다 먼저 돌린다.
 *
 * Spring Boot 의 Flyway 자동 설정을 끈 탓에 Boot 가 걸어 주던 "Flyway 먼저, JPA 나중" 순서도
 * 함께 사라진다. ddl-auto 가 validate 라서 그 순서가 없으면 빈 DB 의 첫 기동이 테이블이 없다는
 * 이유로 실패한다. 그래서 Boot 가 자기 Flyway 에 쓰는 것과 같은 후처리기로 순서를 되돌린다.
 *
 * ApplicationRunner 로 두지 않는다. 그것은 컨텍스트가 다 뜬 뒤에 돌아서 Hibernate 검증보다
 * 늦다.
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

    static final String MIGRATOR_BEAN = "domainFlywayMigrator";

    @Bean(MIGRATOR_BEAN)
    public DomainFlywayMigrator domainFlywayMigrator(DataSource dataSource) {
        return new DomainFlywayMigrator(dataSource);
    }

    /** 엔티티 매니저가 위 빈을 기다리게 한다. */
    @Configuration(proxyBeanMethods = false)
    static class JpaDependency extends EntityManagerFactoryDependsOnPostProcessor {

        JpaDependency() {
            super(MIGRATOR_BEAN);
        }
    }
}
