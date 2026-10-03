package com.launchcatch;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.exception.ErrorCode;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.owner.exception.OwnerErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * 분기와 반복은 전부 아래 헬퍼에 둔다. 테스트 본문은 단언만 한다(UT-3-04).
 */
class ErrorCodeCatalogTest {

    /** 명세의 오류 코드 표가 담은 한 줄. 코드, 상태, 문장이다. */
    private record Row(String code, int status, String message) { }

    private static final Path AUTH_DOC = Path.of("docs/api-spec/auth.md");

    /** auth.md 가 다루는 접두어. 공통 코드는 그 표에 없어 대조 대상이 아니다. */
    private static final Set<String> DOC_PREFIXES = Set.of("AUTH", "OWNER", "MEMBER");

    private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z]{2,10}-[0-9]{3}$");
    private static final Pattern STATUS_FORMAT = Pattern.compile("^[0-9]{3}$");

    /** 표의 칸 수. 양 끝의 빈 칸까지 센다. */
    private static final int CELL_COUNT = 5;

    private static List<ErrorCode> all() {
        return Stream.of(
                        CommonErrorCode.values(),
                        AuthErrorCode.values(),
                        OwnerErrorCode.values(),
                        MemberErrorCode.values())
                .flatMap(Stream::of)
                .map(ErrorCode.class::cast)
                .toList();
    }

    private static List<String> codes() {
        return all().stream().map(ErrorCode::getCode).toList();
    }

    private static List<String> messages() {
        return all().stream().map(ErrorCode::getMessage).toList();
    }

    private static String prefixOf(ErrorCode errorCode) {
        return errorCode.getCode().split("-")[0];
    }

    private static int numberOf(ErrorCode errorCode) {
        return Integer.parseInt(errorCode.getCode().split("-")[1]);
    }

    /** 접두어마다 쓰인 번호를 오름차순으로 모은다. */
    private static Map<String, List<Integer>> numbersByPrefix() {
        return all().stream().collect(groupingBy(
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

    private static String unquote(String cell) {
        return cell.replace("`", "").strip();
    }

    /*
     * 표의 한 줄을 Row 로 읽는다. 코드가 첫 칸인 표만 읽히므로 절별 오류 표는 섞이지 않는다.
     *
     * 정규식으로 줄 전체를 받지 않는다. 문장 칸을 비탐욕 수량자로 잡으면 역추적이 생겨
     * 입력 길이에 비례하지 않게 느려진다. 칸으로 끊고 칸마다 고정된 모양만 본다.
     */
    private static Optional<Row> parseRow(String line) {
        String[] cells = line.strip().split("\\|", -1);
        if (cells.length != CELL_COUNT) {
            return Optional.empty();
        }
        String code = unquote(cells[1]);
        String status = unquote(cells[2]);
        if (!CODE_FORMAT.matcher(code).matches() || !STATUS_FORMAT.matcher(status).matches()) {
            return Optional.empty();
        }
        return Optional.of(new Row(code, Integer.parseInt(status), cells[3].strip()));
    }

    private static Set<Row> rowsInDoc() {
        try (Stream<String> lines = Files.lines(AUTH_DOC)) {
            return lines.map(ErrorCodeCatalogTest::parseRow)
                    .flatMap(Optional::stream)
                    .collect(toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<Row> rowsInCode() {
        return all().stream()
                .filter(errorCode -> DOC_PREFIXES.contains(prefixOf(errorCode)))
                .map(errorCode -> new Row(
                        errorCode.getCode(),
                        errorCode.getHttpStatus().value(),
                        errorCode.getMessage()))
                .collect(toSet());
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

    /*
     * 명세와 코드가 갈리지 않게 한다.
     *
     * auth.md 의 "오류 코드" 표가 클라이언트가 읽는 계약이다. 표에 있는 코드가 코드에 없으면
     * 클라이언트가 못 오는 응답을 기다리고, 상태나 문장이 다르면 화면이 문서대로 안 돈다.
     * 이 검사가 없어서 문서를 고칠 때마다 사람이 눈으로 대조했고, 실제로 어긋난 채 올라갔다.
     */
    @Test
    @DisplayName("auth.md 의 오류 코드 표와 코드가 일치한다")
    void 문서와_일치() {
        assertThat(rowsInDoc())
                .as("auth.md 의 오류 코드 표")
                .isNotEmpty()
                .isEqualTo(rowsInCode());
    }
}
