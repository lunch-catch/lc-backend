package com.launchcatch.global.config;

import com.launchcatch.global.config.DomainFlywayMigrator.SeedSettings;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
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

    /*
     * seed.enabled 가 켜지면 스키마 뒤에 시험 데이터(db/seed)를 넣는다. 기본은 꺼짐이다.
     * seed.password-hash 는 시드 계정(관리자, 점주)의 BCrypt 해시다. 로컬 기본값은 저장소에 공개된 값이라
     * 개발 서버는 인프라가 다른 값을 넣는다.
     * seedBusinessDate 는 오늘(Asia/Seoul)이다. 피드는 오늘 날짜의 행만 읽으므로 날짜에 묶인 시드가 이 값을 쓴다.
     * 값이 바뀌면 그 시드 파일의 체크섬이 바뀌어 하루의 첫 기동에 다시 돈다.
     * seedCdnBaseUrl 은 cdn.base-url 이다. 시드 포스터 HTML 의 이미지 주소 앞에 붙는다. 실제 포스터도 저장할 때 같은 값을 붙인다.
     */
    @Bean(MIGRATOR_BEAN)
    public DomainFlywayMigrator domainFlywayMigrator(
            DataSource dataSource,
            @Value("${seed.enabled:false}") boolean seedEnabled,
            @Value("${seed.password-hash:}") String seedPasswordHash,
            @Value("${cdn.base-url:}") String cdnBaseUrl,
            Clock clock) {
        Map<String, String> placeholders = Map.of(
                "seedPasswordHash", seedPasswordHash,
                "seedBusinessDate", LocalDate.now(clock).toString(),
                "seedCdnBaseUrl", cdnBaseUrl.replaceAll("/+$", ""));
        return new DomainFlywayMigrator(dataSource, new SeedSettings(seedEnabled, placeholders));
    }

    /** 엔티티 매니저가 위 빈을 기다리게 한다. */
    @Configuration(proxyBeanMethods = false)
    static class JpaDependency extends EntityManagerFactoryDependsOnPostProcessor {

        JpaDependency() {
            super(MIGRATOR_BEAN);
        }
    }
}
