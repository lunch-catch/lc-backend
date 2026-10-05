package com.launchcatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.junit.ArchTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 설계 문서가 예고한 아키텍처 테스트와 실제 테스트가 같은지 본다.
 *
 * 문서의 1.5절과 2.5절 표가 팀이 읽는 목록이다. 그 표에만 있고 코드에 없는 규칙은 지켜지지
 * 않는데 지켜진다고 읽히고, 코드에만 있는 규칙은 왜 막혔는지 찾을 곳이 없다. 둘 중 어느 쪽도
 * 사람이 양쪽을 번갈아 보지 않으면 드러나지 않으므로 여기서 맞춘다.
 *
 * 분기와 반복은 헬퍼에 둔다. 테스트 본문은 단언만 한다 (UT-3-04).
 */
class ArchitectureRuleCatalogTest {

    private static final Path DESIGN_DOC =
            Path.of("docs/architecture/런치캐치_도메인_구조와_의존성_설계.md");

    /** 1.5절과 2.5절의 테스트 목록 표. 머리 줄이 이것인 표만 본다. */
    private static final Pattern CATALOG_HEADER =
            Pattern.compile("^\\|\\s*테스트\\s*\\|\\s*지키는 것\\s*\\|");

    /** 표의 구분 줄. 머리 줄 바로 다음 한 줄이다. */
    private static final int SEPARATOR_ROWS = 1;

    private static List<String> lines() {
        try (Stream<String> lines = Files.lines(DESIGN_DOC)) {
            return lines.toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /*
     * 표의 첫 칸에 적힌 테스트 이름을 모은다.
     * 표가 둘이어도 각각 걸리고, 절이 더 생겨도 적지 않아도 걸린다.
     */
    private static List<String> documented() {
        List<String> lines = lines();
        List<String> names = new ArrayList<>();
        for (int header = 0; header < lines.size(); header++) {
            if (!CATALOG_HEADER.matcher(lines.get(header).strip()).find()) {
                continue;
            }
            for (int row = header + 1 + SEPARATOR_ROWS; row < lines.size(); row++) {
                String line = lines.get(row).strip();
                if (!line.startsWith("|")) {
                    break;
                }
                names.add(firstCell(line));
            }
        }
        return names.stream().sorted().toList();
    }

    private static String firstCell(String row) {
        String[] cells = row.split("\\|", -1);
        return cells.length < 2 ? "" : cells[1].replace("`", "").strip();
    }

    /** ArchitectureTest 가 선언한 @ArchTest 의 이름. 필드와 메서드 둘 다 쓸 수 있다. */
    private static List<String> declared() {
        Stream<String> fields = Arrays.stream(ArchitectureTest.class.getDeclaredFields())
                .filter(member -> member.isAnnotationPresent(ArchTest.class))
                .map(Field::getName);
        Stream<String> methods = Arrays.stream(ArchitectureTest.class.getDeclaredMethods())
                .filter(member -> member.isAnnotationPresent(ArchTest.class))
                .map(Method::getName);
        return Stream.concat(fields, methods).sorted().toList();
    }

    /*
     * 양쪽이 비어 있으면 아래 단언이 공허하게 통과한다.
     * 표의 머리 줄 모양이 바뀌거나 애너테이션 이름이 바뀌면 그 상태가 된다.
     */
    @Test
    @DisplayName("문서와 코드 양쪽에서 규칙 목록을 읽어낸다")
    void 목록_탐색() {
        assertThat(documented()).as("문서가 예고한 규칙").isNotEmpty();
        assertThat(declared()).as("코드가 선언한 규칙").isNotEmpty();
    }

    @Test
    @DisplayName("문서가 예고한 규칙과 선언된 규칙이 같다")
    void 문서와_코드가_같다() {
        assertThat(declared())
                .as("ArchitectureTest 가 선언한 @ArchTest")
                .isEqualTo(documented());
    }
}
