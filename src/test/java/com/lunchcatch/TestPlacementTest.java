package com.lunchcatch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/*
 * 테스트의 위치와 이름을 강제한다.
 *
 * 단위 테스트는 대상과 같은 패키지에 두고 이름을 ~Test 로 끝낸다.
 * 통합 테스트는 팀이 권장하지 않지만 필요하다고 판단해 쓸 때가 있다. 그때는
 * @SpringBootTest 로 실제 컨텍스트를 띄우고 이름을 ~IntegrationTest 로 끝낸다
 * (unit-testing-guideline.md 5장).
 *
 * 이름을 강제하는 이유는 커버리지다. 소스셋이 갈려 실행 기록이 test.exec 과
 * integrationTest.exec 으로 나뉘지만, 한 소스셋 안에서 어느 것이 계층을 가로지르는지는
 * 이름으로만 가릴 수 있다.
 *
 * @AnalyzeClasses 를 쓰지 않고 빌드 출력 경로를 직접 읽는다 (#1069). 그 애너테이션은
 * 클래스패스를 훑는데, test 소스셋에서 integrationTest 의 출력이 클래스패스에 없다.
 * 소스셋 사이에 의존을 만들어 넣는 길도 있으나 그러면 test 를 돌릴 때마다 통합 테스트가
 * 함께 컴파일된다. 경로를 직접 읽으면 그 의존이 생기지 않는다.
 *
 * 경로를 직접 읽으면 디렉터리가 비었을 때 검사 대상이 0개가 되어 모든 규칙이 조용히
 * 통과한다. 이 파일이 막으려는 것과 같은 모양이라 가져온 수를 먼저 확인한다.
 */
class TestPlacementTest {

    static final String BASE = "com.lunchcatch";

    private static final Path MAIN_DIR = Path.of("build/classes/java/main");
    private static final Path UNIT_DIR = Path.of("build/classes/java/test");
    private static final Path INTEGRATION_DIR = Path.of("build/classes/java/integrationTest");

    private static final String MAIN = "/classes/java/main/";
    private static final String OWN = "/classes/java/test/";
    private static final String INTEGRATION = "/classes/java/integrationTest/";

    private static final String INTEGRATION_SUFFIX = "IntegrationTest";
    private static final String SPRING_BOOT_TEST =
            "org.springframework.boot.test.context.SpringBootTest";
    private static final String DYNAMIC_PROPERTY_SOURCE =
            "org.springframework.test.context.DynamicPropertySource";
    private static final String SUPPORT = BASE + ".IntegrationTestSupport";
    // ArchitectureTest 의 계층 정의와 같은 이름이다. 통합 테스트를 여기 두지 않는다.
    private static final Set<String> LAYERS = Set.of(
            "controller", "service", "repository", "listener", "job");
    private static final Set<String> CONTAINERS = Set.of(
            "org.testcontainers.containers.GenericContainer",
            "org.testcontainers.mysql.MySQLContainer");

    private static final JavaClasses CLASSES = importAll();

    private static JavaClasses importAll() {
        List<Path> dirs = List.of(MAIN_DIR, UNIT_DIR, INTEGRATION_DIR).stream()
                .filter(Files::isDirectory)
                .collect(Collectors.toList());
        return new ClassFileImporter().importPaths(dirs);
    }

    /*
     * 아래 규칙들이 조용히 통과하는 것을 막는다 (#1069).
     *
     * 경로를 직접 읽으므로 디렉터리 이름이 바뀌거나 컴파일이 돌지 않으면 가져온 클래스가
     * 0개가 되고, 그러면 위반도 0개여서 모든 규칙이 초록이 된다. 검사 대상의 존재를 따로 본다.
     * coverageDataCheck 가 커버리지에서 막는 것과 같은 모양이다.
     */
    @Test
    void 검사할_클래스를_실제로_가져왔다() {
        assertThat(CLASSES.stream().filter(c -> from(c, MAIN)).count())
                .as("main 클래스를 가져오지 못했다. %s 를 확인한다", MAIN_DIR)
                .isGreaterThan(500);
        assertThat(units().size())
                .as("단위 테스트를 가져오지 못했다. %s 를 확인한다", UNIT_DIR)
                .isGreaterThan(200);
        assertThat(tests().size() - units().size())
                .as("통합 테스트를 가져오지 못했다. %s 를 확인한다", INTEGRATION_DIR)
                .isGreaterThan(100);
    }

    private static boolean from(JavaClass c, String dir) {
        return c.getSource().map(s -> s.getUri().toString().contains(dir)).orElse(false);
    }

    /*
     * 베이스 패키지는 아키텍처 테스트 자리다.
     * 도메인에 속하지 않으므로 아래 규칙에서 뺀다.
     */
    private static List<JavaClass> units() {
        return pick(c -> from(c, OWN));
    }

    // 두 소스셋의 테스트를 함께 본다. 통합 테스트가 integrationTest 로 간 뒤에도 규칙이 산다 (#1069).
    private static List<JavaClass> tests() {
        return pick(c -> from(c, OWN) || from(c, INTEGRATION));
    }

    private static List<JavaClass> pick(java.util.function.Predicate<JavaClass> where) {
        return CLASSES.stream()
                .filter(where)
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
    @Test
    void 테스트는_contract에_두지_않는다() {
        List<String> bad = tests().stream()
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
    @Test
    void 프로덕션_패키지를_미러링한다() {
        Set<String> mainPackages = CLASSES.stream()
                .filter(c -> from(c, MAIN))
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());
        List<String> bad = units().stream()
                .filter(c -> !c.getSimpleName().endsWith(INTEGRATION_SUFFIX))
                .filter(c -> !mainPackages.contains(c.getPackageName()))
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + " 에 프로덕션 클래스가 없다)")
                .collect(Collectors.toList());
        fail("단위 테스트 패키지", bad, "대상 클래스와 같은 패키지에 둔다");
    }

    /*
     * 컨테이너는 IntegrationTestSupport 하나가 띄운다.
     *
     * @Container 로 클래스마다 선언하면 수명이 그 클래스에 묶여서 기동과 종료가 테스트 수만큼
     * 반복된다. 러너가 2 vCPU 7GB 인데 앞 컨테이너가 다 내려가기 전에 다음이 올라오는 구간이
     * 생기면 MySQL 이 Connection refused 로 떨어진다. 같은 커밋이 한 번은 통과하고 한 번은
     * 실패하므로 원인을 찾기 어렵다. 어겨도 당장은 깨지지 않고 CI 가 흔들리기 시작할 뿐이라
     * 규칙으로 막는다.
     */
    @Test
    void 컨테이너는_지원_클래스만_띄운다() {
        List<String> bad = CLASSES.stream()
                .filter(c -> from(c, OWN) || from(c, INTEGRATION))
                .filter(c -> !c.getName().equals(SUPPORT))
                .flatMap(c -> c.getFields().stream())
                .filter(f -> CONTAINERS.contains(f.getRawType().getName()))
                .map(f -> f.getOwner().getName() + "." + f.getName())
                .distinct()
                .collect(Collectors.toList());
        fail("컨테이너 선언", bad,
                "IntegrationTestSupport 를 상속한다. 컨테이너는 JVM 당 한 번만 띄운다");
    }

    /*
     * 컨텍스트를 띄우는 테스트는 이름으로 드러낸다.
     * 소스셋이 갈려 실행 기록도 나뉘지만, 한 소스셋 안에서 어느 것이 계층을 가로지르는지는
     * 이름 말고는 가릴 방법이 없다. 컨텍스트를 띄우면서 이 이름이 아닌 클래스를 여기서 막는다.
     */
    @Test
    void 컨텍스트를_띄우는_테스트는_IntegrationTest로_끝난다() {
        List<String> bad = tests().stream()
                .filter(TestPlacementTest::bootsContext)
                .filter(c -> !c.getSimpleName().endsWith(INTEGRATION_SUFFIX))
                .map(JavaClass::getName)
                .collect(Collectors.toList());
        fail("테스트 이름", bad,
                "@SpringBootTest 로 컨텍스트를 띄우면 통합 테스트다. 이름을 ~IntegrationTest 로 끝낸다");
    }

    /*
     * 통합 테스트는 계층 패키지에 두지 않는다.
     *
     * 계층을 가로지르는 테스트라 대상이 한 계층으로 좁혀지지 않는다. service 아래에 두면 그
     * 서비스만 보는 테스트로 읽히고, 옆 계층을 함께 보는 테스트가 그 자리에 쌓인다. 그러면
     * 단위 테스트와 구별되지 않아, 대상과 같은 패키지에 두라는 위의 미러링 규칙과도 부딪힌다.
     *
     * 도메인 패키지나 그 아래의 묶음에 둔다. global/config 처럼 계층이 아닌 묶음은 괜찮다.
     */
    @Test
    void 통합_테스트는_계층_패키지에_두지_않는다() {
        List<String> bad = tests().stream()
                .filter(c -> c.getSimpleName().endsWith(INTEGRATION_SUFFIX))
                .filter(c -> LAYERS.contains(lastSegmentOf(c.getPackageName())))
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + ")")
                .collect(Collectors.toList());
        fail("통합 테스트 위치", bad, "계층 패키지에서 그 위의 도메인 패키지로 옮긴다");
    }

    private static String lastSegmentOf(String packageName) {
        int dot = packageName.lastIndexOf('.');
        return dot < 0 ? packageName : packageName.substring(dot + 1);
    }

    /*
     * 도메인마다 @SpringBootTest 선언을 하나만 둔다.
     *
     * 스프링의 컨텍스트 캐시 키는 테스트 클래스 수가 아니라 빈 오버라이드와 설정의 집합으로
     * 갈린다. 선언을 파일마다 나눠 두면 같은 애플리케이션인데도 그 집합이 갈려서 컨텍스트가
     * 그만큼 늘어난다. 컨텍스트 하나가 힙 20~25MB 를 상주로 쓰고 메타스페이스도 비슷하게
     * 먹는다. 하나가 늘어난 것만으로 G-BUILD 가 떨어진 적이 있다(#277).
     *
     * 그 도메인의 통합 테스트가 함께 쓰는 오버라이드는 Abstract{도메인}IntegrationTest 에
     * 모으고 각 테스트는 그것을 상속한다. 어기면 CI 가 흔들리기 시작할 뿐 당장은 깨지지 않아
     * 규칙으로 막는다.
     */
    @Test
    void 도메인마다_컨텍스트_선언은_하나다() {
        Map<String, List<String>> declarations = tests().stream()
                .filter(TestPlacementTest::bootsContext)
                .collect(Collectors.groupingBy(TestPlacementTest::domainOf, TreeMap::new,
                        Collectors.mapping(JavaClass::getName, Collectors.toList())));
        List<String> bad = declarations.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " 에 " + e.getValue().size() + "건  ("
                        + String.join(", ", e.getValue()) + ")")
                .collect(Collectors.toList());
        fail("도메인당 컨텍스트 선언", bad,
                "그 도메인의 Abstract{도메인}IntegrationTest 에 @SpringBootTest 와 빈 오버라이드를 모으고 상속한다");
    }

    /*
     * 동적 속성은 컨텍스트 선언이 있는 자리에만 둔다.
     *
     * 캐시 키에는 @DynamicPropertySource 메서드의 집합이 들어간다. 상속한 메서드가 같아도
     * 하위 클래스가 하나를 더하면 집합이 달라져서 그 클래스만 자기 컨텍스트를 받는다. 선언을
     * 도메인마다 하나로 모아 둬도 이 축으로 다시 갈리고, 위의 선언 수를 세는 규칙은 그것을
     * 통과시킨다. 전체를 돌리면 갈라진 쪽이 하나뿐이라 수로도 잘 드러나지 않는다(#299).
     *
     * 도메인이 함께 쓸 속성은 그 도메인의 기반 클래스에, 통합 테스트 전체가 쓸 속성은
     * IntegrationTestSupport 의 메서드 안에 둔다. 이미 있는 메서드에 넣으면 집합이 그대로라
     * 키가 늘지 않는다.
     */
    @Test
    void 동적_속성은_컨텍스트_선언과_같은_자리에_둔다() {
        List<String> bad = tests().stream()
                .filter(c -> !bootsContext(c))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(DYNAMIC_PROPERTY_SOURCE))
                .map(m -> m.getOwner().getName() + "." + m.getName() + "()")
                .collect(Collectors.toList());
        fail("동적 속성 선언", bad,
                "그 도메인의 Abstract{도메인}IntegrationTest 나 IntegrationTestSupport 의 메서드로 옮긴다");
    }

    private static String domainOf(JavaClass c) {
        String rest = c.getPackageName().substring(BASE.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
