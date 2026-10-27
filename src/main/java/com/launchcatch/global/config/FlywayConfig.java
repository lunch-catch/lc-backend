package com.launchcatch.global.config;

import com.launchcatch.global.config.DomainFlywayMigrator.BaseSettings;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

    /*
     * 마이그레이션 연결이 서버의 응답을 기다리는 상한이다. 앱 연결(10초)과 다르다.
     * 큰 테이블의 인덱스 생성처럼 오래 도는 DDL 을 클라이언트가 먼저 포기하면 서버의 DDL 은 끝나 스키마가 바뀌고 Flyway 만 실패로 남는다.
     * 그러면 다음 기동마다 이미 생긴 열이나 인덱스에 걸려 멈춘다(#1010). 끊긴 연결을 영영 기다리지 않도록 무제한(0)으로 두지는 않는다.
     */
    static final String MIGRATION_SOCKET_TIMEOUT_MS = "1800000";
    static final String MIGRATION_CONNECT_TIMEOUT_MS = "3000";

    /*
     * base.enabled 가 켜지면 스키마 뒤에 시나리오 데이터(db/base)를 넣는다. 기본은 꺼짐이다.
     * baseDate 는 데이터 날짜의 기준일이다. 비우면 오늘(Asia/Seoul)이다. 시연용 운영은 2026-10-28 을 준다.
     * 계정 비밀번호 해시 넷은 BCrypt 다. 로컬은 local 프로필의 공개 기본값을 쓰고, 개발과 운영은 인프라가 SSM 값을 넣는다.
     * 켜져 있는데 해시가 비면 기동을 막는다. 공개 비밀번호나 빈 비밀번호로 계정이 생기지 않게 한다.
     * baseCdnBaseUrl 은 cdn.base-url 이다. 데이터의 이미지 주소 앞에 붙는다.
     */
    @Bean(MIGRATOR_BEAN)
    public DomainFlywayMigrator domainFlywayMigrator(
            JdbcConnectionDetails connectionDetails,
            @Value("${base.enabled:false}") boolean baseEnabled,
            @Value("${base.date:}") String baseDate,
            @Value("${account.superadmin-password-hash:}") String superadminHash,
            @Value("${account.demo-admin-password-hash:}") String demoAdminHash,
            @Value("${account.demo-owner-password-hash:}") String demoOwnerHash,
            @Value("${account.scenario-password-hash:}") String scenarioHash,
            @Value("${cdn.base-url:}") String cdnBaseUrl,
            Clock clock) {
        Map<String, String> placeholders = Map.of(
                "baseDate", baseDate.isBlank() ? LocalDate.now(clock).toString() : LocalDate.parse(baseDate).toString(),
                "superadminPasswordHash", superadminHash,
                "demoAdminPasswordHash", demoAdminHash,
                "demoOwnerPasswordHash", demoOwnerHash,
                "scenarioPasswordHash", scenarioHash,
                "baseCdnBaseUrl", withoutTrailingSlash(cdnBaseUrl));
        if (baseEnabled) {
            requireHashes(placeholders);
        }
        return new DomainFlywayMigrator(migrationDataSource(connectionDetails), new BaseSettings(baseEnabled, placeholders));
    }

    /*
     * 마이그레이션만 쓰는 연결이다. 앱과 같은 주소와 계정을 쓰되 풀 없이 연결마다 새로 연다.
     * 주소는 설정 값이 아니라 JdbcConnectionDetails 에서 읽는다. 테스트의 Testcontainers 처럼 접속 정보를 설정 밖에서 넣는 환경도 같은 DB 를 본다.
     */
    static DataSource migrationDataSource(JdbcConnectionDetails details) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(details.getJdbcUrl());
        dataSource.setUsername(details.getUsername());
        dataSource.setPassword(details.getPassword());
        Properties properties = new Properties();
        properties.setProperty("connectTimeout", MIGRATION_CONNECT_TIMEOUT_MS);
        properties.setProperty("socketTimeout", MIGRATION_SOCKET_TIMEOUT_MS);
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }

    static void requireHashes(Map<String, String> placeholders) {
        placeholders.forEach((name, value) -> {
            if (name.endsWith("PasswordHash") && value.isBlank()) {
                throw new IllegalStateException(name + " 가 비어 있다. base.enabled 를 켜면 계정 비밀번호 해시 넷이 모두 있어야 한다");
            }
        });
    }

    /*
     * 데이터 HTML 이 주소 뒤에 / 를 붙이므로 끝의 / 를 뗀다.
     * 정규식은 백트래킹이 있어 쓰지 않는다.
     */
    static String withoutTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /** 엔티티 매니저가 위 빈을 기다리게 한다. */
    @Configuration(proxyBeanMethods = false)
    static class JpaDependency extends EntityManagerFactoryDependsOnPostProcessor {

        JpaDependency() {
            super(MIGRATOR_BEAN);
        }
    }
}
