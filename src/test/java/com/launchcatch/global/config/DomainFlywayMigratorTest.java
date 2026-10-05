package com.launchcatch.global.config;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/*
 * 마이그레이션 배치를 검사한다.
 *
 * 여기서 막는 것은 전부 "앱이 정상 기동하면서 테이블만 없는" 실패다. 그 모양은 사람이
 * 알아채기 어렵고, 알아챌 때는 이미 그 테이블을 쓰는 코드가 운영에서 터진 뒤다.
 *
 * 분기와 반복은 헬퍼에 둔다. 테스트 본문은 단언만 한다 (UT-3-04).
 */
class DomainFlywayMigratorTest {

    private static final Path MIGRATION_DIR =
            Path.of("src/main/resources", DomainFlywayMigrator.ROOT);

    private static List<Path> domainDirectories() {
        try (Stream<Path> paths = Files.list(MIGRATION_DIR)) {
            return paths.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> directoryNames() {
        return domainDirectories().stream()
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
    }

    /** 한 폴더에 쓰인 버전 번호. V1__store_init.sql 이면 "1" 이다. */
    private static List<String> versionsIn(Path dir) {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .map(name -> name.replaceFirst("^V", "").replaceFirst("__.*$", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, List<String>> versionsByDomain() {
        return domainDirectories().stream().collect(toMap(
                dir -> dir.getFileName().toString(),
                DomainFlywayMigratorTest::versionsIn));
    }

    private static List<String> duplicatedVersions() {
        return versionsByDomain().entrySet().stream()
                .filter(entry -> entry.getValue().size()
                        != entry.getValue().stream().distinct().count())
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .sorted()
                .toList();
    }

    private static Object flywayEnabled() {
        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            return loaded.getFirst().getProperty("spring.flyway.enabled");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /*
     * 탐색이 비어 있으면 아래 검사가 공허하게 통과한다.
     * 클래스패스 구성이나 패턴이 틀리면 그 상태가 되고, 그때 앱은 마이그레이션을 하나도
     * 돌리지 않는다.
     */
    @Test
    @DisplayName("클래스패스에서 도메인 폴더를 찾아낸다")
    void 도메인_탐색() {
        assertThat(DomainFlywayMigrator.domains()).as("찾아낸 도메인 폴더").isNotEmpty();
    }

    /*
     * 클래스패스로 찾은 목록과 소스 트리의 폴더가 같아야 한다.
     * 어긋나면 리소스가 빌드 산출물에 들어가지 않은 것이고, 그 도메인의 테이블만 없이 기동한다.
     */
    @Test
    @DisplayName("찾아낸 도메인이 마이그레이션 폴더와 일치한다")
    void 탐색_결과가_폴더와_같다() {
        assertThat(DomainFlywayMigrator.domains())
                .as("클래스패스에서 찾은 도메인")
                .isEqualTo(directoryNames());
    }

    /*
     * 도메인 폴더 밖의 SQL 은 어느 이력에도 속하지 않아 영원히 적용되지 않는다.
     * 그런데도 앱은 정상 기동하므로 여기서 잡는다.
     */
    @Test
    @DisplayName("도메인 폴더 밖에 마이그레이션이 없다")
    void 떠돌이_마이그레이션이_없다() {
        assertThat(DomainFlywayMigrator.strayMigrations())
                .as(DomainFlywayMigrator.ROOT + " 바로 아래의 SQL")
                .isEmpty();
    }

    /*
     * 한 폴더 안에서 버전이 겹치면 Flyway 가 기동을 막는다.
     * 폴더가 다르면 겹쳐도 되지만 같은 폴더 안은 안 된다. 두 사람이 같은 도메인에 같은 번호로
     * 파일을 추가하면 생기는 일이라, 합쳐진 시점에 여기서 걸린다.
     */
    @Test
    @DisplayName("한 도메인 안에서 버전이 겹치지 않는다")
    void 도메인_안에서_버전이_겹치지_않는다() {
        assertThat(duplicatedVersions()).as("버전이 겹친 도메인").isEmpty();
    }

    /*
     * 자동 설정이 켜져 있으면 Boot 가 db/migration 전체를 훑어 버전 1 이 도메인 수만큼 걸린다.
     * 그러면 기동이 "Found more than one migration with version 1" 로 멈춘다.
     */
    @Test
    @DisplayName("Flyway 자동 설정이 꺼져 있다")
    void 자동_설정이_꺼져_있다() {
        assertThat(flywayEnabled()).as("spring.flyway.enabled").hasToString("false");
    }
}
