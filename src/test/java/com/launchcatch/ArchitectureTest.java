package com.launchcatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 계층과 도메인 경계를 빌드에서 강제한다 (비기능 31행).
 * 규칙의 근거는 docs/architecture/런치캐치_백엔드_설계.md 1.5절과 2.5절에 있다.
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

    /** 업무 도메인 11개. 설계 문서 1.2절의 표가 원본이다. */
    private static final List<String> DOMAINS = List.of(
            "admin", "owner", "member", "store", "campaign", "poster",
            "billing", "adserving", "coupon", "notification", "analytics");

    /** 다른 도메인에 보이면 안 되는 패키지 세그먼트. 규칙 1 이 막는 대상이다. */
    private static final Set<String> INTERNAL_SEGMENTS = Set.of("entity", "repository");

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

    private static boolean isInternal(JavaClass c) {
        for (String seg : c.getPackageName().split("\\.")) {
            if (INTERNAL_SEGMENTS.contains(seg)) {
                return true;
            }
        }
        return false;
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

    /** 규칙 3. 운영은 아래에서 떠받치는 설정 제공 모듈이라 어떤 업무 도메인도 모른다. */
    @ArchTest
    static final ArchRule 운영은_어떤_도메인도_의존하지_않는다 =
            noClasses().that().resideInAPackage("..ops..")
                    .should().dependOnClassesThat().resideInAnyPackage(domainPackages())
                    .allowEmptyShould(true);

    /** 규칙 5. 인증은 모든 도메인이 올라타는 바닥이라 반대 방향이 없다. */
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
     * 도메인이 아니라 auth 의 역할 검사가 맡는다(규칙 5).
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
     * 규칙 1. 다른 도메인의 entity 와 repository 를 직접 쓰지 않는다.
     *
     * 패키지를 도메인 우선으로 두면 ..repository.. 패턴이 모든 도메인의 리포지토리를 한 계층으로
     * 묶는다. 그래서 adserving.service 가 store.repository.StoreRepository 를 주입받아도
     * 계층_의존_규칙은 통과한다. Service 가 Repository 를 쓰는 것이기 때문이다.
     * 규칙 1 을 실제로 지키는 것은 이 규칙 하나뿐이다.
     *
     * analytics.batch 는 규칙 2 가 인정한 예외라 대상에서 뺀다. 도메인마다 집계용 조회 기능을
     * 따로 만들면 contract 가 도메인 수만큼 늘고 얻는 것이 거의 없다.
     */
    @ArchTest
    static final ArchRule 도메인_내부는_같은_도메인에서만_쓴다 = classes()
            .that().resideOutsideOfPackage("..analytics.batch..")
            .should(new ArchCondition<>("다른 도메인의 entity 와 repository 를 참조하지 않는다") {
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
                        if (!isInternal(target)) {
                            continue;
                        }
                        events.add(SimpleConditionEvent.violated(item,
                                "%s 가 %s 의 내부를 직접 참조한다: %s"
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
     * 규칙 2. 분석의 조회는 집계 테이블만 읽는다.
     * 퍼널 이벤트 원본(analytics.event)과 다른 도메인은 집계 배치만 읽는다. 조회가 원본을 읽기
     * 시작하면 대시보드 조회 부하를 집계로 분리한 이유가 사라진다.
     */
    @ArchTest
    static final ArchRule 분석_조회는_집계만_읽는다 =
            noClasses().that().resideInAPackage("..analytics.query..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(withExtra(domainPackages("analytics"), "..analytics.event.."))
                    .allowEmptyShould(true);

    /** 규칙 6. 두 도메인이 서로를 조회하는 쌍이 없어야 이 규칙이 통과한다. */
    @ArchTest
    static final ArchRule 순환_의존이_없다 =
            slices().matching(BASE + ".(*)..").should().beFreeOfCycles()
                    .allowEmptyShould(true);

    /*
     * 이벤트 타입마다 둘 도메인을 고정한다. 다른 곳에 두면 그 타입 하나를 가리켜 실패한다.
     * 설계 문서 2.4절의 표가 원본이고, 새 이벤트를 만들면 이 목록도 함께 고친다.
     *
     * 발행 도메인의 contract 에 두어야 하는 이유는, 발행자 패키지 안쪽에 두면 수신자가
     * 발행자의 내부를 import 하게 되어 통보로 지운 간선이 import 로 되살아나기 때문이다.
     */
    private static final Map<String, String> EVENT_HOME = Map.ofEntries(
            Map.entry("PointDeductedEvent", "campaign"),
            Map.entry("CampaignEndedEvent", "campaign"),
            Map.entry("CampaignStatusChangedEvent", "campaign"),
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
