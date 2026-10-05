package com.launchcatch;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.global.exception.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 오류 코드 목록을 지킨다. 도메인을 가로지르는 검사라 아키텍처 테스트와 같은 자리에 둔다.
 *
 * 코드는 클라이언트가 화면을 분기하는 값이다. 번호가 겹치거나 비면 클라이언트가 어느 응답을
 * 기다릴지 알 수 없다. 사람이 대조하면 놓치므로 여기서 막는다.
 *
 * enum 은 전부 찾아낸다. 이 파일에 나열하지 않는다. 적어 두면 새 도메인의 enum 을 적지 않은
 * 사람이 그 코드를 검사에서 조용히 빼게 된다.
 *
 * 분기와 반복은 전부 아래 헬퍼에 둔다. 테스트 본문은 단언만 한다(UT-3-04).
 */
class ErrorCodeCatalogTest {

    private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z]{2,10}-[0-9]{3}$");

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
}
