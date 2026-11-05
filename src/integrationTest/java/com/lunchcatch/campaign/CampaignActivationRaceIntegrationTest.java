package com.lunchcatch.campaign;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.dto.CampaignActivateResponse;
import com.lunchcatch.campaign.exception.CampaignErrorCode;
import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.poster.client.PosterImageInspection;
import com.lunchcatch.campaign.poster.dto.PosterUpdateRequest;
import com.lunchcatch.campaign.poster.repository.PosterRepository;
import com.lunchcatch.campaign.poster.service.PosterEditService;
import com.lunchcatch.campaign.poster.service.PosterUploadService;
import com.lunchcatch.campaign.service.CampaignActivationService;
import com.lunchcatch.campaign.template.repository.TemplateRepository;
import com.lunchcatch.global.measure.MeasureSettings;
import com.lunchcatch.global.measure.RaceRunner;
import com.lunchcatch.global.measure.RaceRunner.Outcome;
import com.lunchcatch.global.measure.ScenarioMeasure;
import com.lunchcatch.global.measure.ScenarioMeasure.Condition;
import com.lunchcatch.owner.contract.OwnerStatusQuery;
import com.lunchcatch.store.contract.StoreQueryService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * 캠페인 활성화의 경쟁을 반복해서 잰다(#1090). 불변식과 시나리오의 정의는
 * docs/troubleshooting/campaign/concurrency-measure-definition.md 3.1 절이 갖는다.
 *
 * 클래스 단위 @Transactional 을 NOT_SUPPORTED 로 덮는다. 준비한 행이 커밋되어야 워커 스레드의 다른 연결이 본다.
 *
 * 검수 스텁(PosterImageInspector)은 부르는 순간 포스터의 커밋된 값을 읽어 두고 정해진 시간만큼 기다린 뒤 PASS 를
 * 돌려준다. 기다리는 시간은 실제 S3 와 Rekognition 의 응답 시간이 아니라 가정이다.
 *
 * 포스터 덮어쓰기는 S3 headObject 를 부르므로 진짜 PosterUploadService 빈을 쓰지 않는다. 가짜 S3Client 를 넣어 직접 만든
 * 인스턴스를 쓴다. 빈 오버라이드를 더하면 컨텍스트가 하나 늘어난다(#277).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignActivationRaceIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final long SAMPLING_INTERVAL_MILLIS = 5;

    @Autowired
    private CampaignActivationService activationService;
    @Autowired
    private PosterEditService posterEditService;
    @Autowired
    private PosterRepository posterRepository;
    @Autowired
    private TemplateRepository templateRepository;
    @Autowired
    private StoreQueryService storeQueryService;
    @Autowired
    private OwnerStatusQuery ownerStatusQuery;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private CampaignRaceFixture fixture;
    private Long templateId;
    private PosterUploadService uploadService;

    @BeforeEach
    void setUp() {
        fixture = new CampaignRaceFixture(jdbc, campaignRepository, templateRepository);
        templateId = fixture.newPublishedActiveTemplate();
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentType("image/png").contentLength(1_000L).build());
        uploadService = new PosterUploadService(
                campaignRepository, templateRepository, posterRepository, storeQueryService, ownerStatusQuery,
                mock(S3Presigner.class), s3, transactionTemplate, "https://cdn.example.com");
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(posterImageInspector);
        fixture.deleteAll();
    }

    @Test
    void 활성화와_포스터_덮어쓰기가_겹쳐도_검수하지_않은_포스터로_SCHEDULED_가_되지_않는다() {
        runPosterChangeScenario("activation-poster-overwrite", true);
    }

    @Test
    void 활성화와_포스터_텍스트_수정이_겹쳐도_검수하지_않은_포스터로_SCHEDULED_가_되지_않는다() {
        runPosterChangeScenario("activation-poster-edit", false);
    }

    @Test
    void 같은_캠페인을_동시에_활성화하면_SCHEDULED_는_정확히_한_번이다() {
        MeasureSettings settings = MeasureSettings.fromEnvironment();
        ScenarioMeasure measure = new ScenarioMeasure("activation-concurrent");
        Random random = new Random(MeasureSettings.SEED);
        for (long delay : settings.delaysMillis()) {
            Condition condition = measure.condition("검수 스텁 지연 " + delay + "ms", "A2-1", "A2-2", "A2-3", "A2-4");
            for (int i = 0; i < settings.rounds(); i++) {
                concurrentActivationRound(condition.startRound(), delay, random);
            }
        }
        measure.finish();
    }

    private void runPosterChangeScenario(String scenario, boolean overwrite) {
        MeasureSettings settings = MeasureSettings.fromEnvironment();
        ScenarioMeasure measure = new ScenarioMeasure(scenario);
        Random random = new Random(MeasureSettings.SEED);
        for (long delay : settings.delaysMillis()) {
            Condition condition = measure.condition("검수 스텁 지연 " + delay + "ms", "A1-1", "A1-2", "A1-3", "A1-4");
            for (int i = 0; i < settings.rounds(); i++) {
                posterChangeRound(condition.startRound(), delay, random, overwrite);
            }
        }
        measure.finish();
    }

    private void posterChangeRound(Condition.Round round, long delay, Random random, boolean overwrite) {
        CampaignRaceFixture.Draft draft = fixture.newActivatableDraft();
        Long ownerId = draft.ownerId();
        Long campaignId = draft.campaignId();
        uploadService.createOrOverwritePoster(ownerId, campaignId, templateId, objectKey(campaignId, "initial"));
        ModerationStub stub = stubModeration(campaignId, delay);

        Callable<Object> activate = () -> activationService.activate(ownerId, campaignId);
        Callable<Object> change = overwrite
                ? () -> uploadService.createOrOverwritePoster(
                        ownerId, campaignId, templateId, objectKey(campaignId, "overwritten"))
                : () -> posterEditService.update(ownerId, campaignId,
                        new PosterUpdateRequest(new PosterUpdateRequest.EventName("수정 가게", null), null));
        List<Outcome<Object>> outcomes = RaceRunner.race(List.of(activate, change), delay, random);

        CampaignStatus status = campaignRepository.findById(campaignId).orElseThrow().getStatus();
        Outcome<Object> activation = outcomes.get(0);
        Outcome<Object> changing = outcomes.get(1);
        round.check("A1-1", posterMatchesModeration(status, campaignId, stub, overwrite),
                "status=" + status + " moderatedKey=" + stub.objectKey + " finalPoster="
                        + fixture.readPoster(campaignId));
        round.check("A1-2", !(status == CampaignStatus.DRAFT && respondedScheduled(activation)),
                "status=DRAFT 인데 활성화가 SCHEDULED 로 응답했다");
        round.check("A1-3", status == CampaignStatus.DRAFT || status == CampaignStatus.SCHEDULED, "status=" + status);
        round.check("A1-4", onlyCampaignException(outcomes),
                "activation=" + activation.failure() + " change=" + changing.failure());
        round.count("finalStatus." + status);
        round.count("activation." + describe(activation));
        if (changing.failure() != null) {
            round.count("changeRejected");
        }
        round.sample("changeStartOffsetMillis", changing.startOffsetNanos() / 1_000_000.0);
    }

    private void concurrentActivationRound(Condition.Round round, long delay, Random random) {
        CampaignRaceFixture.Draft draft = fixture.newActivatableDraft();
        Long ownerId = draft.ownerId();
        Long campaignId = draft.campaignId();
        uploadService.createOrOverwritePoster(ownerId, campaignId, templateId, objectKey(campaignId, "initial"));
        ModerationStub stub = stubModeration(campaignId, delay);

        Callable<Object> activate = () -> activationService.activate(ownerId, campaignId);
        List<Outcome<Object>> outcomes;
        ConnectionSampler sampler = new ConnectionSampler(hikariPool());
        sampler.start();
        try {
            outcomes = RaceRunner.race(List.of(activate, activate), delay, random);
        } finally {
            sampler.stop();
        }

        CampaignStatus status = campaignRepository.findById(campaignId).orElseThrow().getStatus();
        long scheduledResponses = outcomes.stream().filter(CampaignActivationRaceIntegrationTest::respondedScheduled).count();
        int logged = fixture.statusChangesToScheduled(campaignId);
        round.check("A2-1", status == CampaignStatus.SCHEDULED, "status=" + status);
        round.check("A2-2", logged == 1, "DRAFT->SCHEDULED 로그 " + logged + "건");
        round.check("A2-3", scheduledResponses == 1, "SCHEDULED 응답 " + scheduledResponses + "건");
        round.check("A2-4", othersRejectedAsStateNotAllowed(outcomes),
                "failures=" + outcomes.stream().map(Outcome::failure).toList());
        int moderationCalls = stub.calls.get();
        round.sample("moderationCalls", moderationCalls);
        if (moderationCalls > 1) {
            round.count("duplicateModeration");
        }
        round.sample("activeConnectionsMax", sampler.max());
        round.sample("activeConnectionsMean", sampler.mean());
    }

    private boolean posterMatchesModeration(
            CampaignStatus status, Long campaignId, ModerationStub stub, boolean overwrite) {
        if (status != CampaignStatus.SCHEDULED) {
            return true;
        }
        CampaignRaceFixture.PosterSnapshot finalPoster = fixture.readPoster(campaignId);
        if (overwrite) {
            return stub.objectKey != null && finalPoster.imageObjectKey().equals(stub.objectKey);
        }
        return stub.snapshot != null && finalPoster.updatedAt().equals(stub.snapshot.updatedAt());
    }

    // 활성화가 어떻게 끝났는지를 한 단어로 센다. 위반이 아니라 분포를 보려는 관측이다.
    private static String describe(Outcome<Object> outcome) {
        if (outcome.failure() instanceof CampaignException e) {
            return "rejected:" + e.getErrorCode().getCode();
        }
        if (outcome.failure() != null) {
            return "error:" + outcome.failure().getClass().getSimpleName();
        }
        return outcome.value() instanceof CampaignActivateResponse response
                ? "response:" + response.status()
                : "response:unknown";
    }

    private static boolean respondedScheduled(Outcome<Object> outcome) {
        return outcome.succeeded() && outcome.value() instanceof CampaignActivateResponse response
                && response.status() == CampaignStatus.SCHEDULED;
    }

    private static boolean onlyCampaignException(List<Outcome<Object>> outcomes) {
        return outcomes.stream().allMatch(outcome -> outcome.failure() == null
                || outcome.failure() instanceof CampaignException);
    }

    private static boolean othersRejectedAsStateNotAllowed(List<Outcome<Object>> outcomes) {
        return outcomes.stream()
                .filter(outcome -> !respondedScheduled(outcome))
                .allMatch(outcome -> outcome.failure() instanceof CampaignException e
                        && e.getErrorCode() == CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
    }

    private static String objectKey(Long campaignId, String name) {
        return "campaigns/" + campaignId + "/poster/" + name + ".png";
    }

    // 검수가 본 포스터와 부른 횟수를 담는다. 스텁이 채우고 회차가 끝난 뒤 읽는다.
    private static final class ModerationStub {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile String objectKey;
        private volatile CampaignRaceFixture.PosterSnapshot snapshot;
    }

    private ModerationStub stubModeration(Long campaignId, long delayMillis) {
        ModerationStub stub = new ModerationStub();
        doAnswer(call -> {
            stub.calls.incrementAndGet();
            stub.objectKey = call.getArgument(0);
            stub.snapshot = fixture.readPoster(campaignId);
            Thread.sleep(delayMillis);
            return PosterImageInspection.read(300, 300, 0.1, List.of());
        }).when(posterImageInspector).inspect(any());
        return stub;
    }

    private HikariPoolMXBean hikariPool() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (SQLException e) {
            throw new IllegalStateException("커넥션 풀이 Hikari 가 아니다", e);
        }
    }

    // 활성 커넥션 수를 일정 간격으로 읽어 최대값과 평균을 낸다. 읽기만 하고 커넥션을 빌리지 않는다.
    private static final class ConnectionSampler {

        private final HikariPoolMXBean pool;
        private final List<Integer> readings = new ArrayList<>();
        private volatile boolean running;
        private Thread thread;

        private ConnectionSampler(HikariPoolMXBean pool) {
            this.pool = pool;
        }

        private void start() {
            running = true;
            thread = new Thread(() -> {
                while (running) {
                    synchronized (readings) {
                        readings.add(pool.getActiveConnections());
                    }
                    try {
                        Thread.sleep(SAMPLING_INTERVAL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "hikari-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        private void stop() {
            running = false;
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private double max() {
            synchronized (readings) {
                return readings.stream().mapToInt(Integer::intValue).max().orElse(0);
            }
        }

        private double mean() {
            synchronized (readings) {
                return readings.stream().mapToInt(Integer::intValue).average().orElse(0);
            }
        }
    }
}
