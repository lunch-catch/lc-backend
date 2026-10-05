package com.launchcatch.global.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/*
 * 도메인마다 Flyway 를 따로 돌린다. 설계 문서 1.3절이 정한 방식이다.
 *
 * 마이그레이션은 도메인별 폴더에 있고 버전은 도메인마다 1부터 붙는다. 그래서 전체를 한 번에
 * 훑으면 버전 1 이 도메인 수만큼 걸려 Flyway 가 "Found more than one migration with version 1"
 * 로 멈춘다. 도메인마다 위치와 이력 테이블을 따로 주어 각 이력이 자기 폴더만 보게 한다.
 * 그래서 Spring Boot 의 Flyway 자동 설정은 끈다(application.yml 의 spring.flyway.enabled).
 */
public class DomainFlywayMigrator implements InitializingBean {

    static final String ROOT = "db/migration";

    /** 도메인 폴더 안의 SQL 경로에서 폴더 이름을 뽑는다. jar 안의 경로에도 걸린다. */
    private static final Pattern DOMAIN_OF = Pattern.compile(ROOT + "/([^/]+)/[^/]+\\.sql$");

    private final DataSource dataSource;

    public DomainFlywayMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /*
     * 엔티티 매니저보다 먼저 돌아야 한다. ddl-auto 가 validate 라서 순서가 뒤집히면 빈 DB 에서
     * Hibernate 가 테이블이 없다고 기동을 막는다. 그 순서는 FlywayConfig 가 고정한다.
     */
    @Override
    public void afterPropertiesSet() {
        List<String> stray = strayMigrations();
        if (!stray.isEmpty()) {
            throw new IllegalStateException(
                    ROOT + " 바로 아래에 둔 마이그레이션은 어느 도메인에도 속하지 않아 적용되지 않는다: "
                            + stray + ". 도메인 폴더로 옮긴다");
        }
        domains().forEach(this::migrate);
    }

    private void migrate(String domain) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:" + ROOT + "/" + domain)
                .table("flyway_history_" + domain)
                /*
                 * 두 번째 도메인부터는 앞 도메인이 만든 테이블 때문에 스키마가 비어 있지 않다.
                 * 그 상태에서 자기 이력 테이블이 없으면 Flyway 가 기동을 거부하므로 켠다.
                 * 기본 baselineVersion 은 1 인데 그러면 V1 이 이미 적용된 것으로 간주되어
                 * 각 도메인의 첫 마이그레이션이 조용히 건너뛰어진다. 0 으로 내려 그것을 막는다.
                 */
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /*
     * 마이그레이션 폴더를 훑어 도메인 이름을 모은다. 목록을 코드에 적지 않는다.
     * 적어 두면 새 도메인 폴더를 추가한 사람이 이 목록을 함께 고쳐야 하고, 잊으면 그 도메인의
     * 테이블만 없는 채로 앱이 기동한다. 폴더가 곧 목록이다.
     */
    static List<String> domains() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> found = Arrays.stream(resources(resolver, "classpath*:" + ROOT + "/*/*.sql"))
                .map(DomainFlywayMigrator::domainOf)
                .distinct()
                .sorted()
                .toList();
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    ROOT + " 아래에서 도메인 폴더를 찾지 못했다. 마이그레이션이 하나도 돌지 않는다");
        }
        return found;
    }

    /*
     * 도메인 폴더 밖에 놓인 SQL 을 찾는다.
     * 그 파일은 어느 도메인에도 속하지 않아 영원히 적용되지 않는데, 앱은 정상 기동한다.
     * 조용히 빠지는 것이라 사람이 알아채지 못하므로 기동을 막는다.
     */
    static List<String> strayMigrations() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return Arrays.stream(resources(resolver, "classpath*:" + ROOT + "/*.sql"))
                .map(Resource::getFilename)
                .sorted()
                .toList();
    }

    private static Resource[] resources(ResourcePatternResolver resolver, String pattern) {
        try {
            return resolver.getResources(pattern);
        } catch (IOException e) {
            throw new UncheckedIOException(ROOT + " 를 훑지 못했다", e);
        }
    }

    private static String domainOf(Resource resource) {
        String path = pathOf(resource);
        Matcher matcher = DOMAIN_OF.matcher(path);
        if (!matcher.find()) {
            throw new IllegalStateException("도메인 폴더를 읽을 수 없는 마이그레이션 경로다: " + path);
        }
        return matcher.group(1);
    }

    private static String pathOf(Resource resource) {
        try {
            return resource.getURL().getPath();
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션 경로를 읽지 못했다", e);
        }
    }
}
