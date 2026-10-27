package com.lunchcatch.global.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    /*
     * 시나리오 데이터다 (#741). 스키마와 섞지 않고 따로 둔다. 이력도 flyway_history_base 로 따로 둔다.
     * 섞으면 스키마 버전과 데이터가 서로의 순서를 막는다.
     * V__ 는 기본 세계, R__demo 는 시연 층이다 (docs/scenarios/data.md, demo.md).
     */
    static final String BASE_ROOT = "db/base";

    /** 도메인 폴더 안의 SQL 경로에서 폴더 이름을 뽑는다. jar 안의 경로에도 걸린다. */
    private static final Pattern DOMAIN_OF = Pattern.compile(ROOT + "/([^/]+)/[^/]+\\.sql$");

    private final DataSource dataSource;
    private final BaseSettings base;

    public DomainFlywayMigrator(DataSource dataSource) {
        this(dataSource, BaseSettings.DISABLED);
    }

    public DomainFlywayMigrator(DataSource dataSource, BaseSettings base) {
        this.dataSource = dataSource;
        this.base = base;
    }

    /*
     * 시나리오 데이터를 돌릴지와 SQL 의 자리표시자 값이다.
     * 기본은 꺼짐이다. 로컬은 local 프로필이, 개발과 운영은 인프라가 BASE_ENABLED 로 켠다. 테스트는 켜지 않는다.
     */
    public record BaseSettings(boolean enabled, Map<String, String> placeholders) {

        public static final BaseSettings DISABLED = new BaseSettings(false, Map.of());

        public BaseSettings {
            placeholders = Map.copyOf(placeholders);
        }
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
        if (base.enabled()) {
            migrateBase();
        }
    }

    /*
     * 모든 도메인이 끝난 뒤 한 번 돈다. 데이터는 점주, 가게, 캠페인처럼 여러 도메인 테이블에 걸친다.
     * V__ 는 DB 마다 한 번만 돈다. 이미 적용된 V__ 를 고치면 체크섬이 어긋나 기동이 막히므로 바꿀 것은 새 V__ 로 더한다.
     * R__demo 는 내용이 바뀌면 다시 돌므로 여러 번 돌아도 같은 결과가 되게 쓴다.
     */
    private void migrateBase() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:" + BASE_ROOT)
                .table("flyway_history_base")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholders(base.placeholders())
                .load()
                .migrate();
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
