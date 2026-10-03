package com.launchcatch;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.global.exception.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 오류 코드 목록을 지킨다. 도메인을 가로지르는 검사라 아키텍처 테스트와 같은 자리에 둔다.
 *
 * 코드는 클라이언트가 화면을 분기하는 값이다. 번호가 겹치거나 비거나, 문서와 코드의 문장이
 * 갈리면 클라이언트가 어느 쪽을 믿을지 알 수 없다. 사람이 대조하면 놓치므로 여기서 막는다.
 *
 * enum 과 문서를 모두 찾아낸다. 어느 쪽도 이 파일에 나열하지 않는다.
 * 전에는 enum 넷과 문서 둘을 적어 두었는데 그것이 지뢰였다. 새 도메인의 enum 을 적지 않으면
 * 그 코드가 검사에서 조용히 빠지고, 새 문서를 적지 않으면 올바르게 쓴 문서가 "코드에만 있다" 로
 * 떨어진다. 둘 다 이 파일을 모르는 사람이 밟는다.
 *
 * 분기와 반복은 전부 아래 헬퍼에 둔다. 테스트 본문은 단언만 한다(UT-3-04).
 */
class ErrorCodeCatalogTest {

    /** 명세의 오류 코드 표가 담은 한 줄. 코드, 상태, 문장이다. */
    private record Row(String code, int status, String message) { }

    private static final Path DOCS = Path.of("docs");

    private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z]{2,10}-[0-9]{3}$");
    private static final Pattern STATUS_FORMAT = Pattern.compile("^[0-9]{3}$");

    /*
     * 오류 코드 표를 절 제목이 아니라 머리 줄로 찾는다.
     *
     * 제목으로 찾으면 "오류 코드" 가 든 다른 절의 표까지 걸린다. auth.md 의 절별 오류 표는
     * 상태가 첫 칸이고 열 구성이 달라서, 제목으로 찾는 방식에서는 그것이 형식 위반으로 보인다.
     * 머리 줄이 코드, 상태, 메시지로 시작하는 표만 목록으로 본다.
     */
    private static final Pattern CATALOG_HEADER =
            Pattern.compile("^\\|\\s*코드\\s*\\|\\s*상태\\s*\\|\\s*메시지\\s*\\|");

    /*
     * 양 끝의 빈 칸까지 센 최소 칸 수. 코드, 상태, 문장 세 열이면 다섯이다.
     * 열이 더 있는 표도 있어서 같은지가 아니라 이것보다 적지 않은지를 본다.
     */
    private static final int MIN_CELLS = 5;

    /** 표의 구분 줄. 머리 줄 바로 다음 한 줄이다. */
    private static final int SEPARATOR_ROWS = 1;

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.launchcatch");

    /*
     * ErrorCode 를 구현한 enum 을 전부 찾아 상수를 모은다.
     *
     * getEnumConstants 가 null 인 것을 버린다. 상수가 본문을 가지면(isExpectedTraffic 을
     * 재정의하는 AUTH-003 과 AUTH-005 가 그렇다) 컴파일러가 익명 하위 클래스를 만들고
     * 바이트코드에 enum 표시가 함께 붙는다. ArchUnit 은 그것도 enum 으로 보는데 그 클래스에는
     * 상수가 없어 null 이 온다. 걸러 내지 않으면 여기서 NullPointerException 이 난다.
     */
    private static List<ErrorCode> all() {
        return PRODUCTION_CLASSES.stream()
                .filter(JavaClass::isEnum)
                .filter(javaClass -> javaClass.isAssignableTo(ErrorCode.class))
                .map(JavaClass::reflect)
                .map(Class::getEnumConstants)
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .map(ErrorCode.class::cast)
                .sorted(Comparator.comparing(ErrorCode::getCode))
                .toList();
    }

    private static List<String> codes() {
        return all().stream().map(ErrorCode::getCode).toList();
    }

    private static List<String> messages() {
        return all().stream().map(ErrorCode::getMessage).toList();
    }

    private static String prefixOf(String code) {
        return code.split("-")[0];
    }

    private static int numberOf(String code) {
        return Integer.parseInt(code.split("-")[1]);
    }

    /** 접두어마다 쓰인 번호를 오름차순으로 모은다. */
    private static Map<String, List<Integer>> numbersByPrefix() {
        return codes().stream().collect(groupingBy(
                ErrorCodeCatalogTest::prefixOf,
                TreeMap::new,
                mapping(ErrorCodeCatalogTest::numberOf,
                        collectingAndThen(toList(), found -> found.stream().sorted().toList()))));
    }

    /** 접두어마다 1부터 끊기지 않고 이어진 번호. 실제와 이것을 한 번에 견준다. */
    private static Map<String, List<Integer>> expectedNumbersByPrefix() {
        return numbersByPrefix().entrySet().stream().collect(toMap(
                Map.Entry::getKey,
                entry -> IntStream.rangeClosed(1, entry.getValue().size()).boxed().toList(),
                (left, right) -> left,
                TreeMap::new));
    }

    private static List<Path> markdownFiles() {
        try (Stream<Path> paths = Files.walk(DOCS)) {
            return paths.filter(path -> path.toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(Path doc) {
        try {
            return Files.readAllLines(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /*
     * docs 전체에서 오류 코드 표의 본문 줄을 모은다.
     *
     * 머리 줄을 만나면 구분 줄을 건너뛰고 파이프로 시작하지 않는 줄까지 모은다.
     * 한 문서에 표가 여럿이어도 각각 걸리고, 새 문서가 생기면 적지 않아도 걸린다.
     */
    private static List<String> catalogRows() {
        List<String> rows = new ArrayList<>();
        for (Path doc : markdownFiles()) {
            List<String> lines = lines(doc);
            for (int header = 0; header < lines.size(); header++) {
                if (!CATALOG_HEADER.matcher(lines.get(header).strip()).find()) {
                    continue;
                }
                for (int row = header + 1 + SEPARATOR_ROWS; row < lines.size(); row++) {
                    String line = lines.get(row).strip();
                    if (!line.startsWith("|")) {
                        break;
                    }
                    rows.add(line);
                }
            }
        }
        return rows;
    }

    private static String unquote(String cell) {
        return cell.replace("`", "").strip();
    }

    /*
     * 표의 한 줄을 Row 로 읽는다.
     *
     * 정규식으로 줄 전체를 받지 않는다. 문장 칸을 비탐욕 수량자로 잡으면 역추적이 생겨
     * 입력 길이에 비례하지 않게 느려진다. 칸으로 끊고 칸마다 고정된 모양만 본다.
     */
    private static Optional<Row> parseRow(String line) {
        String[] cells = line.strip().split("\\|", -1);
        if (cells.length < MIN_CELLS) {
            return Optional.empty();
        }
        String code = unquote(cells[1]);
        String status = unquote(cells[2]);
        if (!CODE_FORMAT.matcher(code).matches() || !STATUS_FORMAT.matcher(status).matches()) {
            return Optional.empty();
        }
        return Optional.of(new Row(code, Integer.parseInt(status), cells[3].strip()));
    }

    private static List<String> malformedRows() {
        return catalogRows().stream()
                .filter(line -> parseRow(line).isEmpty())
                .toList();
    }

    private static Set<Row> rowsInDoc() {
        return catalogRows().stream()
                .map(ErrorCodeCatalogTest::parseRow)
                .flatMap(Optional::stream)
                .collect(toSet());
    }

    private static Set<Row> rowsInCode() {
        return all().stream()
                .map(errorCode -> new Row(
                        errorCode.getCode(),
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage()))
                .collect(toSet());
    }

    /*
     * 탐색이 비어 있으면 아래 검사가 모두 공허하게 통과한다.
     * 패키지 이름이 바뀌거나 가져오기 설정이 틀리면 그 상태가 된다.
     */
    @Test
    @DisplayName("ErrorCode 를 구현한 enum 을 찾아낸다")
    void enum_탐색() {
        assertThat(all()).as("찾아낸 오류 코드").isNotEmpty();
    }

    @Test
    @DisplayName("코드 형식은 도메인-번호 이고 세 자리 번호다")
    void 코드_형식() {
        assertThat(codes())
                .allMatch(code -> CODE_FORMAT.matcher(code).matches(), "도메인-번호 형식");
    }

    @Test
    @DisplayName("코드가 겹치지 않는다")
    void 코드_중복() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    /*
     * 번호가 1부터 끊기지 않고 이어져야 한다.
     * 중간이 비면 지운 것인지 아직 안 쓴 것인지 알 수 없고, 다음 사람이 빈 번호를 다시 쓴다.
     */
    @Test
    @DisplayName("접두어마다 번호가 1부터 끊기지 않는다")
    void 번호_연속() {
        assertThat(numbersByPrefix())
                .as("접두어별로 쓰인 번호")
                .isEqualTo(expectedNumbersByPrefix());
    }

    @Test
    @DisplayName("메시지는 비어 있지 않고 앞뒤 공백이 없다")
    void 메시지_형식() {
        assertThat(messages())
                .allMatch(message -> !message.isBlank() && message.equals(message.strip()),
                        "비어 있지 않고 앞뒤 공백이 없다");
    }

    @Test
    @DisplayName("오류 코드 표의 모든 행이 코드와 상태 형식을 지킨다")
    void 표_행_형식() {
        assertThat(malformedRows())
                .as("오류 코드 표에서 코드나 상태를 읽을 수 없는 행")
                .isEmpty();
    }

    /*
     * 명세와 코드가 갈리지 않게 한다.
     *
     * 문서의 오류 코드 표가 클라이언트가 읽는 계약이다. 표에 있는 코드가 코드에 없으면
     * 클라이언트가 못 오는 응답을 기다리고, 상태나 문장이 다르면 화면이 문서대로 안 돈다.
     * 거꾸로 코드에만 있으면 클라이언트가 받고도 뜻을 찾을 곳이 없다.
     */
    @Test
    @DisplayName("문서의 오류 코드 표와 코드가 일치한다")
    void 문서와_일치() {
        assertThat(rowsInDoc())
                .as("문서의 오류 코드 표")
                .isNotEmpty()
                .isEqualTo(rowsInCode());
    }
}
