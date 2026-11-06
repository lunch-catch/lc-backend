package com.lunchcatch.campaign.template;

import com.lunchcatch.campaign.AbstractCampaignIntegrationTest;
import com.lunchcatch.campaign.CampaignRaceFixture;
import com.lunchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.lunchcatch.campaign.template.dto.TemplateCreateResponse;
import com.lunchcatch.campaign.template.repository.TemplateRepository;
import com.lunchcatch.campaign.template.repository.TemplateVersionRepository;
import com.lunchcatch.campaign.template.service.TemplateGenerationRateLimiter;
import com.lunchcatch.campaign.template.service.TemplateReviseService;
import com.lunchcatch.global.measure.MeasureSettings;
import com.lunchcatch.global.measure.RaceRunner;
import com.lunchcatch.global.measure.RaceRunner.Outcome;
import com.lunchcatch.global.measure.ScenarioMeasure;
import com.lunchcatch.global.measure.ScenarioMeasure.Condition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 같은 템플릿을 여러 관리자가 동시에 수정하는 경쟁을 반복해서 잰다(#1090). 불변식과 시나리오의 정의는
 * docs/troubleshooting/campaign/concurrency-measure-definition.md 3.2 절이 갖는다.
 *
 * 서비스를 직접 부르고 호출마다 다른 관리자 ID 와 requestId 를 쓴다. 관리자당 분당 3회 제한과 같은 requestId
 * 재시도 판정에 걸리지 않게 하려는 것이다.
 *
 * 저장 구간의 대기 시간을 재려고 TransactionTemplate 을 시간을 기록하는 것으로 감싼 TemplateReviseService 를 직접
 * 만든다. 빈 오버라이드를 더하면 컨텍스트가 하나 늘어난다(#277). 모델은 bedrock.model-id 가 없을 때 켜지는
 * FakeTemplateHtmlGenerator 가 고정 HTML 로 대신하므로 AWS 없이 돈다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TemplateReviseRaceIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final int CONCURRENT_ADMINS = 8;
    private static final int MAX_LIVE_VERSIONS = 20;
    private static final long FIRST_ADMIN_ID = 10_000_000L;
    private static final String PROMPT = "버튼 색 바꿔줘";

    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private TemplateVersionRepository templateVersionRepository;
    @Autowired
    private TemplateHtmlGenerator templateHtmlGenerator;
    @Autowired
    private TemplateHtmlSanitizer templateHtmlSanitizer;
    @Autowired
    private TemplateSlotValidator templateSlotValidator;
    @Autowired
    private TemplateStructureValidator templateStructureValidator;
    @Autowired
    private TemplatePromptValidator templatePromptValidator;
    @Autowired
    private TemplateGenerationRateLimiter rateLimiter;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong nextAdminId = new AtomicLong(FIRST_ADMIN_ID);
    private CampaignRaceFixture fixture;
    private TimingTransactionTemplate timing;
    private TemplateReviseService reviseService;

    @BeforeEach
    void setUp() {
        fixture = new CampaignRaceFixture(jdbc, campaignRepository, templateRepository);
        timing = new TimingTransactionTemplate(transactionManager, transactionTemplate);
        reviseService = new TemplateReviseService(
                templateRepository, templateVersionRepository, templateHtmlGenerator, templateHtmlSanitizer,
                templateSlotValidator, templateStructureValidator, templatePromptValidator, rateLimiter, timing,
                clock);
    }

    @AfterEach
    void tearDown() {
        fixture.deleteAll();
    }

    @Test
    void 같은_템플릿을_동시에_수정해도_버전_번호가_겹치거나_빠지지_않고_상한을_넘지_않는다() {
        int rounds = MeasureSettings.fromEnvironment().rounds();
        ScenarioMeasure measure = new ScenarioMeasure("template-revise-concurrent");
        Random random = new Random(MeasureSettings.SEED);
        for (int initialVersions : new int[] {1, MAX_LIVE_VERSIONS - 2}) {
            Condition condition = measure.condition(
                    "수정 전 버전 " + initialVersions + "개, 동시 수정 " + CONCURRENT_ADMINS + "명",
                    "B1", "B2", "B3", "B4", "B5", "B6");
            for (int i = 0; i < rounds; i++) {
                reviseRound(condition.startRound(), initialVersions, random);
            }
        }
        measure.finish();
    }

    private void reviseRound(Condition.Round round, int initialVersions, Random random) {
        Long templateId = fixture.newDraftTemplateWithVersions(initialVersions);
        timing.drain();
        List<Callable<TemplateCreateResponse>> actions = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ADMINS; i++) {
            Long adminId = nextAdminId.getAndIncrement();
            String requestId = "race-revise-" + UUID.randomUUID();
            actions.add(() -> reviseService.revise(templateId, PROMPT, requestId, adminId));
        }

        List<Outcome<TemplateCreateResponse>> outcomes = RaceRunner.race(actions, 0, random);

        List<Integer> numbers = outcomes.stream()
                .filter(Outcome::succeeded)
                .map(outcome -> outcome.value().versionNumber())
                .sorted()
                .toList();
        round.check("B1", numbers.size() == CONCURRENT_ADMINS,
                "성공 " + numbers.size() + "건, 실패 " + failures(outcomes));
        round.check("B2", new HashSet<>(numbers).size() == numbers.size(), "응답 번호 " + numbers);
        round.check("B3", isConsecutive(numbers, initialVersions + 1), "응답 번호 " + numbers);

        int[] rowCounts = rowCounts(templateId);
        round.check("B4", rowCounts[0] == rowCounts[1], "행 " + rowCounts[0] + "건, 서로 다른 번호 " + rowCounts[1] + "개");
        List<Integer> live = liveVersionNumbers(templateId);
        round.check("B5", live.size() <= MAX_LIVE_VERSIONS, "살아 있는 버전 " + live.size() + "개");
        round.check("B6", isConsecutiveEndingAt(live, initialVersions + CONCURRENT_ADMINS),
                "살아 있는 번호 " + live);

        timing.drain().forEach(nanos -> round.sample("saveSegmentMillis", nanos / 1_000_000.0));
        outcomes.forEach(outcome -> round.sample("reviseTotalMillis", outcome.elapsedNanos() / 1_000_000.0));
        round.sample("liveVersions", live.size());
        outcomes.stream().filter(outcome -> !outcome.succeeded())
                .forEach(outcome -> round.count("failed." + outcome.failure().getClass().getSimpleName()));
    }

    // 번호가 first 에서 시작해 빈틈없이 이어지는지 본다. 목록은 오름차순이다.
    private static boolean isConsecutive(List<Integer> sorted, int first) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i) != first + i) {
                return false;
            }
        }
        return true;
    }

    // 번호가 빈틈없이 이어지고 마지막이 last 인지 본다. 목록은 오름차순이다.
    private static boolean isConsecutiveEndingAt(List<Integer> sorted, int last) {
        return !sorted.isEmpty() && sorted.get(sorted.size() - 1) == last
                && isConsecutive(sorted, sorted.get(0));
    }

    private static List<String> failures(List<Outcome<TemplateCreateResponse>> outcomes) {
        return outcomes.stream().filter(outcome -> !outcome.succeeded())
                .map(outcome -> outcome.failure().toString()).toList();
    }

    // 소프트삭제된 행까지 센다. (template_id, version_number) UNIQUE 가 그 번호를 쥐고 있기 때문이다.
    private int[] rowCounts(Long templateId) {
        return jdbc.queryForObject(
                "select count(*), count(distinct version_number) from template_version where template_id = ?",
                (rs, rowNum) -> new int[] {rs.getInt(1), rs.getInt(2)}, templateId);
    }

    private List<Integer> liveVersionNumbers(Long templateId) {
        return jdbc.queryForList(
                "select version_number from template_version"
                        + " where template_id = ? and deleted_at is null order by version_number",
                Integer.class, templateId);
    }

    // execute 가 걸린 시간을 기록한다. 저장 구간이 행 잠금을 기다린 시간이 여기 들어간다.
    private static final class TimingTransactionTemplate extends TransactionTemplate {

        private final Queue<Long> elapsedNanos = new ConcurrentLinkedQueue<>();

        // 빈의 전파, 격리 수준, 제한 시간을 그대로 물려받는다.
        private TimingTransactionTemplate(PlatformTransactionManager manager, TransactionTemplate source) {
            super(manager, source);
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            long start = System.nanoTime();
            try {
                return super.execute(action);
            } finally {
                elapsedNanos.add(System.nanoTime() - start);
            }
        }

        private List<Long> drain() {
            List<Long> drained = new ArrayList<>();
            Long next;
            while ((next = elapsedNanos.poll()) != null) {
                drained.add(next);
            }
            return drained;
        }
    }
}
