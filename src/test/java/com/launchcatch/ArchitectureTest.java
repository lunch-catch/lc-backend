package com.launchcatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.redis.core.RedisTemplate;

/*
 * 계층과 도메인 경계를 빌드에서 강제한다.
 * 규칙의 근거는 docs/architecture/런치캐치_도메인_구조와_의존성_설계.md 1.5절과 2.5절에 있다.
 *
 * 도메인이 생기기 전에 넣는다. 클래스가 쌓인 뒤에 넣으면 이미 깨진 것을 무더기로 만나
 * 고치는 대신 규칙을 끄게 된다.
 *
 * 그래서 규칙마다 allowEmptyShould(true) 를 붙인다. ArchUnit 은 검사 대상이 0개인 규칙을
 * 기본으로 실패시키는데, 지금은 대부분이 그 상태다. 규칙이 잠자고 있다가 첫 클래스가
 * 생기는 순간부터 문다.
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE = "com.launchcatch";

    /*
     * 업무 도메인 10개. 설계 문서 1.2절의 표가 원본이다.
     * global, auth, ops 는 누구나 쓰는 하위 모듈이라 여기 없다.
     */
    private static final List<String> DOMAINS = List.of(
            "admin", "owner", "member", "store", "campaign",
            "billing", "adserving", "coupon", "notification", "analytics");

    private static String[] domainPackages(String... except) {
        List<String> excluded = Arrays.asList(except);
        return DOMAINS.stream()
                .filter(d -> !excluded.contains(d))
                .map(d -> ".." + d + "..")
                .toArray(String[]::new);
    }

    /** com.launchcatch.store.repository.StoreRepository -> "store" */
    private static String domainOf(JavaClass c) {
        String p = c.getPackageName();
        if (!p.startsWith(BASE + ".")) {
            return "";
        }
        String rest = p.substring(BASE.length() + 1);
        int dot = rest.indexOf('.');
        String head = dot < 0 ? rest : rest.substring(0, dot);
        return DOMAINS.contains(head) ? head : "";
    }

    /** 그 도메인의 공개면인지 본다. com.launchcatch.store.contract 와 그 아래가 공개면이다. */
    private static boolean isContract(JavaClass c, String domain) {
        String contract = BASE + "." + domain + ".contract";
        String p = c.getPackageName();
        return p.equals(contract) || p.startsWith(contract + ".");
    }

    // --- 1.5절. 계층을 지키는 테스트 -------------------------------------------------

    /*
     * 설계 문서 1.4절의 표를 그대로 옮긴 것이다.
     * 진입점은 둘이다. 바깥 HTTP 로 들어오는 Controller 와, 이벤트와 스케줄로 들어오는 Entry.
     * contract 는 계층이 아니라 계약이므로 층으로 넣지 않는다. 인터페이스와 record, enum 만
     * 있어 부를 코드가 없다.
     */
    @ArchTest
    static final ArchRule 계층_의존_규칙 = layeredArchitecture().consideringAllDependencies()
            .layer("Controller").definedBy("..controller..")
            .layer("Entry").definedBy("..listener..", "..job..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Entry").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Entry")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .allowEmptyShould(true);

    /** 규칙 5. 운영은 아래에서 떠받치는 설정 제공 모듈이라 어떤 업무 도메인도 모른다. */
    @ArchTest
    static final ArchRule 운영은_어떤_도메인도_의존하지_않는다 =
            noClasses().that().resideInAPackage("..ops..")
                    .should().dependOnClassesThat().resideInAnyPackage(domainPackages())
                    .allowEmptyShould(true);

    /** 규칙 3. 인증은 모든 도메인이 올라타는 바닥이라 반대 방향이 없다. */
    @ArchTest
    static final ArchRule 인증_모듈은_어떤_도메인도_의존하지_않는다 =
            noClasses().that().resideInAPackage("..auth..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(withExtra(domainPackages(), "..ops.."))
                    .allowEmptyShould(true);

    /** global 은 가장 아래 모듈이다. 도메인 지식이 없어야 거리 계산과 응답 포맷을 모두가 쓴다. */
    @ArchTest
    static final ArchRule 기술_공통은_아무것도_의존하지_않는다 =
            noClasses().that().resideInAPackage("..global..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(withExtra(domainPackages(), "..ops..", "..auth.."))
                    .allowEmptyShould(true);

    /*
     * 기능을 데이터 소유 도메인에 둔다는 결정을 고정한다.
     * 관리자 화면에 점주 정보를 붙이려고 admin 에서 owner 를 부르기 시작하면 관리자 도메인이
     * 다시 커지고, 계정을 셋으로 나눈 이유가 사라진다. 관리자만 부를 수 있다는 제약은
     * 도메인이 아니라 auth 의 역할 검사가 맡는다(규칙 3).
     */
    @ArchTest
    static final ArchRule 관리자는_다른_업무_도메인을_의존하지_않는다 =
            noClasses().that().resideInAPackage("..admin..")
                    .should().dependOnClassesThat().resideInAnyPackage(domainPackages("admin"))
                    .allowEmptyShould(true);

    /*
     * contract 에 로직이 들어갈 자리를 없앤다 (설계 문서 1.3절).
     * 조회와 명령을 모두 인터페이스로 두고 구현은 내부 service 에 둔다. 그래야 다른 도메인이
     * 보는 것이 계약뿐이고, 내부를 리팩터링해도 남의 코드가 바뀌지 않는다.
     */
    @ArchTest
    static final ArchRule contract에는_인터페이스_record_enum만_둔다 = classes()
            .that().resideInAPackage("..contract..")
            .should(new ArchCondition<>("인터페이스나 record, enum 이다") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    if (!c.isTopLevelClass()) {
                        return;
                    }
                    if (c.isInterface() || c.isEnum() || isRecord(c)) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(c,
                            c.getName() + " 는 인터페이스도 record 도 enum 도 아니다. 구현은 service 에 둔다"));
                }
            })
            .allowEmptyShould(true);

    /** contract 인터페이스와 record 가 엔티티를 드러내면 계약을 지나 내부가 새어 나간다. */
    @ArchTest
    static final ArchRule contract는_entity와_repository를_참조하지_않는다 =
            noClasses().that().resideInAPackage("..contract..")
                    .should().dependOnClassesThat().resideInAnyPackage("..entity..", "..repository..")
                    .allowEmptyShould(true);

    // --- 2.5절. 그래프를 지키는 테스트 -----------------------------------------------

    /*
     * 규칙 1. 다른 업무 도메인은 contract 패키지만 참조한다.
     *
     * entity 와 repository 만 막지 않는다. service 와 controller 도 막는다. 남의 service 를
     * 직접 부르면 그 도메인이 무엇을 공개하기로 했는지와 무관하게 내부 구현에 묶이고, 그 쪽이
     * 리팩터링할 때마다 이 쪽이 깨진다. 공개면을 contract 하나로 좁히는 것이 규칙 1 이다.
     *
     * 계층_의존_규칙으로는 이것을 잡을 수 없다. 패키지를 도메인 우선으로 두면 ..repository..
     * 패턴이 모든 도메인의 리포지토리를 한 계층으로 묶어서, adserving.service 가
     * store.repository.StoreRepository 를 주입받아도 "Service 가 Repository 를 쓴다" 로 보여
     * 통과한다. 규칙 1 을 실제로 지키는 것은 이 규칙 하나뿐이다.
     *
     * analytics.batch 는 규칙 4 가 인정한 예외라 대상에서 뺀다. 도메인마다 집계용 조회 기능을
     * 따로 만들면 contract 가 도메인 수만큼 늘고 얻는 것이 거의 없다.
     */
    @ArchTest
    static final ArchRule 다른_도메인은_contract만_참조한다 = classes()
            .that().resideOutsideOfPackage("..analytics.batch..")
            .should(new ArchCondition<>("다른 업무 도메인의 contract 만 참조한다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    String mine = domainOf(item);
                    if (mine.isEmpty()) {
                        return;
                    }
                    for (Dependency d : item.getDirectDependenciesFromSelf()) {
                        JavaClass target = d.getTargetClass();
                        String theirs = domainOf(target);
                        if (theirs.isEmpty() || theirs.equals(mine)) {
                            continue;
                        }
                        if (isContract(target, theirs)) {
                            continue;
                        }
                        events.add(SimpleConditionEvent.violated(item,
                                "%s 가 %s 의 contract 밖을 참조한다: %s"
                                        .formatted(mine, theirs, d.getDescription())));
                    }
                }
            })
            .allowEmptyShould(true);

    /*
     * 규칙 1. 도메인 간 연관은 객체 참조가 아니라 ID 로만 갖는다.
     * 예를 들어 Coupon 은 Campaign 객체가 아니라 campaignId 를 갖는다.
     */
    @ArchTest
    static final ArchRule 엔티티는_다른_도메인_엔티티를_참조하지_않는다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should(new ArchCondition<>("다른 도메인의 엔티티를 참조하지 않는다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    String mine = domainOf(item);
                    for (Dependency d : item.getDirectDependenciesFromSelf()) {
                        JavaClass target = d.getTargetClass();
                        if (!target.isAnnotatedWith(Entity.class)) {
                            continue;
                        }
                        String theirs = domainOf(target);
                        if (theirs.isEmpty() || theirs.equals(mine)) {
                            continue;
                        }
                        events.add(SimpleConditionEvent.violated(item,
                                "%s 의 엔티티가 %s 의 엔티티를 참조한다. 연관은 ID 로만 갖는다: %s"
                                        .formatted(mine, theirs, d.getDescription())));
                    }
                }
            })
            .allowEmptyShould(true);

    /*
     * 규칙 4. 분석의 조회는 집계 테이블만 읽는다.
     * 퍼널 이벤트 원본(analytics.event)과 다른 도메인은 집계 배치만 읽는다. 조회가 원본을 읽기
     * 시작하면 대시보드 조회 부하를 집계로 분리한 이유가 사라진다.
     */
    @ArchTest
    static final ArchRule 분석_조회는_집계만_읽는다 =
            noClasses().that().resideInAPackage("..analytics.query..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(withExtra(domainPackages("analytics"), "..analytics.event.."))
                    .allowEmptyShould(true);

    /** 규칙 2. 두 도메인이 서로를 조회하는 쌍이 없어야 이 규칙이 통과한다. */
    @ArchTest
    static final ArchRule 순환_의존이_없다 =
            slices().matching(BASE + ".(*)..").should().beFreeOfCycles()
                    .allowEmptyShould(true);

    /*
     * 규칙 1. 캐시 키도 자기 도메인 접두어만 쓴다.
     *
     * 키를 직접 만들면 남의 접두어를 쓰거나 같은 접두어를 둘이 쓰는 것을 아무도 막지 못한다.
     * 그 사고는 테이블과 달리 흔적이 남지 않아서, 값이 덮어쓰여도 원인을 찾기 어렵다.
     * 그래서 업무 도메인은 RedisTemplate 을 직접 주입받지 않고 자기 이름으로 만든 키스페이스만
     * 받는다.
     *
     * auth, ops, global 은 업무 도메인이 아니라 대상에서 빠진다. 셋은 모든 도메인이 올라타는
     * 하위 모듈이고 각자 자기 네임스페이스를 소유한다.
     */
    @ArchTest
    static final ArchRule 캐시_키는_자기_도메인_접두어만_쓴다 =
            noClasses().that().resideInAnyPackage(domainPackages())
                    .should().dependOnClassesThat().areAssignableTo(RedisTemplate.class)
                    .allowEmptyShould(true);

    /*
     * 규칙 1. 네이티브 쿼리에 다른 도메인의 테이블 이름이 없다.
     *
     * JPQL 은 엔티티 타입을 쓰므로 다른 도메인의 테이블을 읽으면 import 가 생겨 위 규칙에 걸린다.
     * 네이티브 쿼리는 문자열이라 import 없이 남의 테이블을 읽을 수 있고, 그래서 경계를 넘는
     * 유일한 구멍이다.
     *
     * 테이블 소유는 마이그레이션 폴더로 판정한다. 표로 적어 두면 테이블을 더한 사람이 그 표도
     * 함께 고쳐야 하고, 잊으면 그 테이블은 아무 도메인 것도 아니어서 검사에서 조용히 빠진다.
     *
     * 소유를 모르는 이름은 넘긴다. 이름이 걸리지 않은 것은 별칭이나 함수일 수 있어서, 그것까지
     * 위반으로 보면 멀쩡한 쿼리가 빨갛게 된다. 여기서 잡을 것은 "남의 테이블"뿐이다.
     */
    @ArchTest
    static final ArchRule 네이티브_쿼리는_자기_도메인_테이블만_쓴다 = classes()
            .should(new ArchCondition<>("네이티브 쿼리가 자기 도메인 테이블만 쓴다") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    String mine = domainOf(item);
                    if (mine.isEmpty()) {
                        return;
                    }
                    for (String table : nativeQueryTables(item)) {
                        String owner = TABLE_OWNER.get(table);
                        if (owner == null || owner.equals(mine)) {
                            continue;
                        }
                        events.add(SimpleConditionEvent.violated(item,
                                "%s 의 네이티브 쿼리가 %s 의 테이블 %s 를 쓴다. 그 도메인의 contract 로 읽는다"
                                        .formatted(mine, owner, table)));
                    }
                }
            })
            .allowEmptyShould(true);

    /*
     * 이벤트 타입마다 둘 도메인을 고정한다. 다른 곳에 두면 그 타입 하나를 가리켜 실패한다.
     * 설계 문서 2.4절의 표가 원본이고, 새 이벤트를 만들면 이 목록도 함께 고친다.
     *
     * 발행 도메인의 contract 에 두어야 하는 이유는, 발행자 패키지 안쪽에 두면 수신자가
     * 발행자의 내부를 import 하게 되어 통보로 지운 간선이 import 로 되살아나기 때문이다.
     */
    private static final Map<String, String> EVENT_HOME = Map.ofEntries(
            Map.entry("CampaignEndedEvent", "campaign"),
            Map.entry("CampaignStatusChangedEvent", "campaign"),
            Map.entry("StoreFinalizedEvent", "store"),
            Map.entry("ImpressionServedEvent", "adserving"),
            Map.entry("SwipedEvent", "adserving"),
            Map.entry("CouponIssuedEvent", "coupon"),
            Map.entry("CouponIssueOpenedEvent", "coupon"),
            Map.entry("CouponRedeemedEvent", "coupon"),
            Map.entry("CouponExpiredEvent", "coupon"),
            Map.entry("NotificationSentEvent", "notification"));

    @ArchTest
    static final ArchRule 이벤트_타입은_정해진_도메인_contract에_둔다 = classes()
            .that().haveSimpleNameEndingWith("Event")
            .and().resideOutsideOfPackage("..analytics..")
            .should(new ArchCondition<>("목록에 적힌 도메인의 contract 에 있다") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    String home = EVENT_HOME.get(c.getSimpleName());
                    if (home == null) {
                        events.add(SimpleConditionEvent.violated(c,
                                c.getName() + " 가 목록에 없다. 설계 문서 2.4절의 표와 이 목록을 함께 고친다"));
                    } else if (!c.getPackageName().equals(BASE + "." + home + ".contract")) {
                        events.add(SimpleConditionEvent.violated(c,
                                c.getName() + " 는 " + home + ".contract 에 있어야 한다"));
                    }
                }
            })
            .allowEmptyShould(true);

    private static String[] withExtra(String[] base, String... extra) {
        String[] merged = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, merged, base.length, extra.length);
        return merged;
    }

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);

    /** 네이티브 쿼리에서 테이블 이름이 올 자리. 하위 쿼리는 여는 괄호라 걸리지 않는다. */
    private static final Pattern TABLE_SLOT = Pattern.compile(
            "(?:FROM|JOIN|INTO|UPDATE)\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);

    /*
     * 테이블 소유 목록이 비면 네이티브_쿼리 규칙이 영원히 통과한다.
     * 마이그레이션 경로가 바뀌거나 작업 디렉터리가 달라지면 그 상태가 된다.
     */
    @Test
    void 테이블_소유_탐색() {
        assertThat(TABLE_OWNER).as("마이그레이션에서 읽은 테이블 소유").isNotEmpty();
    }

    /*
     * 테이블 이름 -> 소유 도메인. 마이그레이션 폴더가 원본이다.
     * db/migration/{도메인}/*.sql 의 CREATE TABLE 을 모은다.
     * 위의 세 필드를 쓰므로 선언이 그 뒤에 와야 한다. 정적 초기화는 적은 순서대로 돈다.
     */
    private static final Map<String, String> TABLE_OWNER = tableOwners();

    private static Map<String, String> tableOwners() {
        Map<String, String> owners = new HashMap<>();
        try (Stream<Path> paths = Files.walk(MIGRATION_DIR)) {
            paths.filter(path -> path.toString().endsWith(".sql")).forEach(path -> {
                String domain = path.getParent().getFileName().toString();
                CREATE_TABLE.matcher(read(path)).results()
                        .forEach(found -> owners.put(found.group(1).toLowerCase(Locale.ROOT), domain));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(owners);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 그 클래스의 @Query(nativeQuery = true) 들이 가리키는 테이블 이름. */
    private static Set<String> nativeQueryTables(JavaClass c) {
        Set<String> tables = new LinkedHashSet<>();
        for (JavaMethod method : c.getMethods()) {
            if (!method.isAnnotatedWith(Query.class)) {
                continue;
            }
            Query query = method.getAnnotationOfType(Query.class);
            if (!query.nativeQuery()) {
                continue;
            }
            TABLE_SLOT.matcher(query.value()).results()
                    .forEach(found -> tables.add(found.group(1).toLowerCase(Locale.ROOT)));
        }
        return tables;
    }

    /*
     * JavaClass.isRecord() 는 ArchUnit 판에 따라 없을 수 있다.
     * 상위 클래스가 java.lang.Record 인지로 본다. 판이 올라가도 이 판정은 그대로 맞는다.
     */
    private static boolean isRecord(JavaClass c) {
        return c.getRawSuperclass()
                .map(s -> "java.lang.Record".equals(s.getName()))
                .orElse(false);
    }
}
