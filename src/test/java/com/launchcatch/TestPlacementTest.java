package com.launchcatch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * 테스트의 위치와 이름을 강제한다.
 *
 * 단위 테스트는 대상과 같은 패키지에 두고 이름을 ~Test 로 끝낸다.
 * 통합 테스트는 팀이 권장하지 않지만 필요하다고 판단해 쓸 때가 있다. 그때는
 * @SpringBootTest 로 실제 컨텍스트를 띄우고 이름을 ~IntegrationTest 로 끝낸다
 * (unit-testing-guideline.md 5장).
 *
 * 이름을 강제하는 이유는 커버리지다. 둘이 같은 소스셋에 있어 실행 기록이 test.exec 하나로
 * 모이므로, 어느 것이 계층을 가로지르는 테스트인지 이름으로만 가릴 수 있다.
 */
@AnalyzeClasses(packages = TestPlacementTest.BASE, importOptions = TestPlacementTest.MainAndUnitTests.class)
class TestPlacementTest {

    static final String BASE = "com.launchcatch";

    private static final String MAIN = "/classes/java/main/";
    private static final String OWN = "/classes/java/test/";

    private static final String INTEGRATION_SUFFIX = "IntegrationTest";
    private static final String SPRING_BOOT_TEST =
            "org.springframework.boot.test.context.SpringBootTest";

    static class MainAndUnitTests implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return location.contains(MAIN) || location.contains(OWN);
        }
    }

    private static boolean from(JavaClass c, String dir) {
        return c.getSource().map(s -> s.getUri().toString().contains(dir)).orElse(false);
    }

    /*
     * 베이스 패키지는 아키텍처 테스트 자리다.
     * 도메인에 속하지 않으므로 아래 규칙에서 뺀다.
     */
    private static List<JavaClass> own(JavaClasses classes) {
        return classes.stream()
                .filter(c -> from(c, OWN))
                .filter(JavaClass::isTopLevelClass)
                .filter(c -> !c.getPackageName().equals(BASE))
                .collect(Collectors.toList());
    }

    private static boolean bootsContext(JavaClass c) {
        return c.isAnnotatedWith(SPRING_BOOT_TEST);
    }

    private static boolean underContract(JavaClass c) {
        String p = c.getPackageName();
        return p.contains(".contract.") || p.endsWith(".contract");
    }

    private static void fail(String rule, List<String> bad, String how) {
        if (!bad.isEmpty()) {
            throw new AssertionError(rule + " 위반 " + bad.size() + "건\n  "
                    + String.join("\n  ", bad) + "\n" + how);
        }
    }

    /*
     * contract 에는 테스트를 두지 않는다.
     * 그 패키지에는 인터페이스와 record, enum 만 있어 동작이 없다(설계 문서 1.3절).
     * 계약이 지켜지는지는 구현의 테스트가 본다. 구현은 같은 도메인의 service 에 있다.
     */
    @ArchTest
    static void 테스트는_contract에_두지_않는다(JavaClasses classes) {
        List<String> bad = own(classes).stream()
                .filter(TestPlacementTest::underContract)
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + ")")
                .collect(Collectors.toList());
        fail("단위 테스트 위치", bad, "contract 에는 동작이 없다. 구현이 있는 service 쪽으로 옮긴다");
    }

    /*
     * 대상과 정확히 같은 패키지에 둔다.
     * Controller 나 contract 구현체를 package-private 으로 두면 패키지가 어긋나는 순간 닿지 못한다.
     *
     * 통합 테스트는 뺀다. 계층을 가로지르므로 대상이 한 패키지로 좁혀지지 않고,
     * 도메인 패키지 바로 아래에 두는 편이 자연스럽다.
     */
    @ArchTest
    static void 프로덕션_패키지를_미러링한다(JavaClasses classes) {
        Set<String> mainPackages = classes.stream()
                .filter(c -> from(c, MAIN))
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());
        List<String> bad = own(classes).stream()
                .filter(c -> !c.getSimpleName().endsWith(INTEGRATION_SUFFIX))
                .filter(c -> !mainPackages.contains(c.getPackageName()))
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + " 에 프로덕션 클래스가 없다)")
                .collect(Collectors.toList());
        fail("단위 테스트 패키지", bad, "대상 클래스와 같은 패키지에 둔다");
    }

    /*
     * 컨텍스트를 띄우는 테스트는 이름으로 드러낸다.
     * 소스셋이 하나라서 실행 기록이 test.exec 으로 모이고, 커버리지 숫자가 단위 테스트에서
     * 나온 것인지 계층을 가로지르는 테스트에서 나온 것인지 이름 말고는 가릴 방법이 없다.
     */
    @ArchTest
    static void 컨텍스트를_띄우는_테스트는_IntegrationTest로_끝난다(JavaClasses classes) {
        List<String> bad = own(classes).stream()
                .filter(TestPlacementTest::bootsContext)
                .filter(c -> !c.getSimpleName().endsWith(INTEGRATION_SUFFIX))
                .map(JavaClass::getName)
                .collect(Collectors.toList());
        fail("테스트 이름", bad,
                "@SpringBootTest 로 컨텍스트를 띄우면 통합 테스트다. 이름을 ~IntegrationTest 로 끝낸다");
    }
}
