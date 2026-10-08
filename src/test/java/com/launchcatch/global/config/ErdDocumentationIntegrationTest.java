package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * docs/erd 의 문서가 지금 스키마와 같은지 본다.
 *
 * 생성과 검사를 한 클래스에 둔다. 생성기를 따로 두면 그것이 도는 경로와 검사가 보는 경로가
 * 갈라져서, 그림이 멀쩡해 보이는데 틀린 상태가 생긴다.
 *
 * 기본 동작은 검사다. 빌드가 작업 트리를 더럽히면 git status 가 매번 지저분해지고, 그 상태로
 * 커밋하는 사람이 생긴다. 다시 뽑는 것은 -Derd.write=true 를 준 사람의 명시적 행동이어야 한다.
 * ./gradlew generateErd 가 그 속성을 붙여 이 시험을 돌린다.
 */
@Testcontainers
@SpringBootTest
class ErdDocumentationIntegrationTest {

    /*
     * Mermaid 의 속성 타입이 받는 글자다. 이 밖의 것이 섞이면 그림이 통째로 렌더링되지 않는다.
     * MySQL 의 decimal(10,7) 과 enum('A','B') 가 그 경우이고 ErdWriter 가 정제한다.
     * 정제가 새 타입을 놓치는 것을 여기서 전수로 잡는다.
     */
    private static final Pattern MERMAID_TYPE = Pattern.compile("[A-Za-z0-9_()\\[\\]]+");

    /** 이 속성이 true 면 검사하지 않고 문서를 다시 쓴다. generateErd 태스크가 붙인다. */
    private static final String WRITE_PROPERTY = "erd.write";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    /** 운영 비밀이 아니라 컨텍스트를 띄우기 위한 자리 채움이다. */
    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("docs/erd 가 지금 스키마와 같다")
    void erd_문서가_스키마와_같다() {
        ErdWriter writer = new ErdWriter(dataSource);
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            writer.write();
            assertThat(ErdWriter.OUTPUT_DIR).as("다시 쓴 출력 디렉터리").exists();
            return;
        }
        assertThat(stale(writer.render()))
                .as("스키마와 어긋난 문서. ./gradlew generateErd 로 다시 뽑는다")
                .isEmpty();
    }

    /*
     * 어긋난 파일 이름만 모은다. 내용 차이를 메시지에 담지 않는다. 63개 테이블의 diff 를 단언
     * 메시지에 쏟으면 무엇이 틀렸는지 더 안 보인다. 다시 뽑아서 git diff 로 보는 것이 빠르다.
     */
    private static List<String> stale(Map<String, String> expected) {
        List<String> mismatched = new ArrayList<>();
        expected.forEach((domain, body) -> {
            Path path = ErdWriter.OUTPUT_DIR.resolve(domain + ".md");
            if (!Files.exists(path)) {
                mismatched.add(domain + ".md (없음)");
            } else if (!read(path).equals(body)) {
                mismatched.add(domain + ".md (내용 다름)");
            }
        });
        return mismatched;
    }

    /*
     * 타입 하나가 문법을 어기면 그 도메인 그림 전체가 안 그려진다. 한 칸이 비는 것이 아니라
     * 코드 블록이 글자로 남는다. 그래서 생성 결과를 믿지 않고 토큰을 전부 본다.
     */
    @Test
    @DisplayName("모든 속성 타입이 Mermaid 가 받는 글자만 쓴다")
    void 속성_타입이_렌더링된다() {
        assertThat(unsafeTypes(new ErdWriter(dataSource).render()))
                .as("Mermaid 가 거부하는 타입")
                .isEmpty();
    }

    private static List<String> unsafeTypes(Map<String, String> documents) {
        return documents.values().stream()
                .flatMap(String::lines)
                .filter(line -> line.startsWith("        "))
                .map(line -> line.strip().split(" ", 2)[0])
                .filter(type -> !MERMAID_TYPE.matcher(type).matches())
                .distinct()
                .toList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /*
     * 마이그레이션 폴더가 생겼는데 문서를 안 뽑은 경우를 잡는다. 위 검사는 생성한 쪽을 기준으로
     * 돌므로 반대 방향, 즉 쓰이지 않는 문서가 남아 있는 것은 보지 못한다.
     */
    @Test
    @DisplayName("docs/erd 에 도메인 수만큼의 문서가 있다")
    void 문서_수가_도메인_수와_같다() {
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            return;
        }
        assertThat(documentNames())
                .as("docs/erd 의 문서")
                .containsExactlyInAnyOrderElementsOf(expectedNames());
    }

    /** 도메인마다 한 장과 색인 한 장이다. */
    private static List<String> expectedNames() {
        List<String> names = new ArrayList<>(
                DomainFlywayMigrator.domains().stream().map(domain -> domain + ".md").toList());
        names.add(ErdWriter.INDEX_NAME + ".md");
        return names;
    }

    private static List<String> documentNames() {
        if (!Files.isDirectory(ErdWriter.OUTPUT_DIR)) {
            return List.of();
        }
        try (var paths = Files.list(ErdWriter.OUTPUT_DIR)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
