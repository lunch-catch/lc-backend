package com.launchcatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.global.exception.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 오류 코드 목록을 지킨다. 도메인을 가로지르는 검사라 아키텍처 테스트와 같은 자리에 둔다.
 *
 * 코드의 형식과 중복, 메시지의 공백을 검사한다.
 * 오류를 통합하거나 삭제하면 번호가 비어도 허용한다.
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

    @Test
    @DisplayName("메시지는 비어 있지 않고 앞뒤 공백이 없다")
    void 메시지_형식() {
        assertThat(messages())
                .allMatch(message -> !message.isBlank() && message.equals(message.strip()),
                        "비어 있지 않고 앞뒤 공백이 없다");
    }
}
