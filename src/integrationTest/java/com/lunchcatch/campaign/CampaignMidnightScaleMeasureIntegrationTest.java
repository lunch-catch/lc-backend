package com.lunchcatch.campaign;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lunchcatch.campaign.CampaignScaleFixture.Kind;
import com.lunchcatch.campaign.service.CampaignDailyTransitionService;
import com.lunchcatch.campaign.service.CampaignStoreSummaryService;
import com.lunchcatch.global.measure.ScaleMeasureSettings;
import com.lunchcatch.global.measure.ScenarioMeasure;
import com.lunchcatch.global.measure.ScenarioMeasure.Condition;
import com.lunchcatch.global.measure.ScenarioMeasure.Condition.Round;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 00:00 묶음 중 캠페인이 맡은 네 단계의 소요 시간을 캠페인 규모와 데이터 오류 비율별로 잰다(#1091).
 * 데이터 분포와 측정 방법의 정의는 docs/troubleshooting/campaign/midnight-scale-measure-definition.md 가 갖는다.
 *
 * MEASURE_SCALE 이 있을 때만 돈다. 없으면 건너뛰어 ./gradlew check 의 시간과 PR 게이트에 영향을 주지 않는다.
 * Gradle 은 환경변수를 입력으로 보지 않아 같은 코드로 다시 돌릴 때는 --rerun 을 붙여야 한다.
 *
 * 네 단계를 서비스 메서드로 하나씩 직접 불러 nanoTime 으로 잰다. 단계가 캠페인 상태를 바꾸므로 한 번 잴 때마다 처음
 * 상태로 되돌리고, 되돌리는 시간은 재지 않는다. 그래서 단계마다 처음 상태에서 출발한 시간이다. 실제 배치처럼 앞 단계가
 * 끝난 상태에서 이어서 도는 시간이 아니다. 단계마다 예열 1번을 버리고 MEASURE_ROUNDS 번을 남긴다.
 *
 * 로그는 재는 동안 줄인다. 캠페인 한 건마다 쓰는 DEBUG 로그가 시간에 섞이기 때문이다. 줄이는 일은 Logback 레벨을 런타임에
 * 바꾸는 것이라 컨텍스트 캐시 키에 영향이 없고, 끝나면 원래 레벨로 돌려놓는다.
 *
 * 단언은 회차마다 오류 없이 끝나고 처리 건수가 같은지에만 건다. 시간은 기록만 한다.
 *
 * 클래스 단위 @Transactional 을 NOT_SUPPORTED 로 덮는다. 서비스가 자기 트랜잭션을 열고 준비한 행이 커밋되어 있어야 한다.
 */
@EnabledIfEnvironmentVariable(named = ScaleMeasureSettings.SCALE_ENV, matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignMidnightScaleMeasureIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final LocalDate DATE = CampaignScaleFixture.BUSINESS_DATE;
    private static final double[] ERROR_RATES = {0.0, 0.001, 0.01};
    private static final long MIB = 1024L * 1024L;
    private static final int CHUNK_SIZE = 500;

    private static final String END = "endExpired";
    private static final String START = "startScheduled";
    private static final String PREPARE = "prepare";
    private static final String BUDGET = "applyPendingDailyBudgets";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private CampaignDailyTransitionService transitionService;
    @Autowired
    private CampaignStoreSummaryService summaryService;

    private CampaignScaleFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.deleteAll();
        }
    }

    private record Step(String name, IntSupplier run, int targets) {
    }

    @Test
    void 네_단계의_소요_시간을_규모와_오류_비율별로_잰다() {
        ScaleMeasureSettings settings = ScaleMeasureSettings.fromEnvironment();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        ScenarioMeasure measure = new ScenarioMeasure("midnight-scale");
        fixture = new CampaignScaleFixture(jdbc);

        try (LogControl logs = new LogControl()) {
            for (int scale : settings.scales()) {
                fixture.create(scale);
                for (double rate : ERROR_RATES) {
                    fixture.applyErrorRate(rate);
                    fixture.captureBaseline();
                    Condition condition = measure.condition(
                            "규모 " + scale + ", 오류 " + percent(rate), "NO_EXCEPTION", "SAME_COUNT", "ALL_TARGETS");
                    measureCondition(condition, statistics, logs, settings.rounds(), rate);
                    System.out.println(measure.summaryTable());
                    measure.writeJson(Path.of("build", "measure"));
                }
                fixture.deleteAll();
            }
        }
        measure.finish();
    }

    private void measureCondition(Condition condition, Statistics statistics, LogControl logs, int rounds, double rate) {
        List<Step> steps = List.of(
                new Step(END, () -> transitionService.endExpired(DATE),
                        fixture.count(Kind.END_ACTIVE) + fixture.count(Kind.END_SCHEDULED)),
                new Step(START, () -> transitionService.startScheduled(DATE),
                        fixture.count(Kind.START_PLAIN) + fixture.count(Kind.START_REPLACE)),
                new Step(PREPARE, () -> summaryService.prepare(DATE),
                        fixture.count(Kind.REPLACED_ACTIVE) + fixture.count(Kind.RUNNING)
                                + fixture.count(Kind.PENDING_BUDGET)),
                new Step(BUDGET, () -> transitionService.applyPendingDailyBudgets(DATE),
                        fixture.count(Kind.PENDING_BUDGET)));

        Map<String, Integer> firstProcessed = new HashMap<>();
        Round last = null;
        // 0번은 예열이라 버린다. 회차 하나가 네 단계를 한 번씩 돈다.
        for (int run = 0; run <= rounds; run++) {
            Round round = run == 0 ? null : condition.startRound();
            for (Step step : steps) {
                fixture.restoreBaseline();
                Integer processed = runOnce(step, round, statistics, logs);
                if (round == null || processed == null) {
                    continue;
                }
                Integer first = firstProcessed.computeIfAbsent(step.name(), name -> processed);
                round.check("SAME_COUNT", processed.equals(first),
                        step.name() + " 처리 " + processed + "건, 첫 회차 " + first + "건");
                if (rate == 0.0) {
                    round.check("ALL_TARGETS", processed == step.targets(),
                            step.name() + " 처리 " + processed + "건, 대상 " + step.targets() + "건");
                }
            }
            last = round == null ? last : round;
        }
        fixture.restoreBaseline();
        last.count("maxHeapMb", Runtime.getRuntime().maxMemory() / MIB);
    }

    // 단계를 한 번 돌리고 처리 건수를 돌려준다. 예외나 힙 부족이면 null 이다. round 가 null 이면 예열이라 기록하지 않는다.
    private Integer runOnce(Step step, Round round, Statistics statistics, LogControl logs) {
        System.gc();
        statistics.clear();
        logs.clear();
        long heapBefore = usedHeap();
        long dbBefore = dbStatements();
        Integer processed = null;
        boolean outOfMemory = false;
        long elapsedNanos;
        long heapPeak;
        try (HeapSampler sampler = new HeapSampler()) {
            long begin = System.nanoTime();
            try {
                processed = step.run().getAsInt();
            } catch (OutOfMemoryError e) {
                outOfMemory = true;
            } catch (RuntimeException e) {
                if (round != null) {
                    round.check("NO_EXCEPTION", false, step.name() + ": " + e);
                }
            }
            elapsedNanos = System.nanoTime() - begin;
            heapPeak = sampler.peak();
        }
        long dbStatements = dbStatements() - dbBefore;
        if (round == null) {
            return processed;
        }
        round.check("NO_EXCEPTION", true, "");
        if (outOfMemory) {
            round.count(step.name() + "_outOfMemory");
            return null;
        }
        if (processed == null) {
            return null;
        }
        double seconds = elapsedNanos / 1_000_000_000.0;
        round.sample(step.name() + "_ms", seconds * 1000.0);
        round.sample(step.name() + "_processed", processed);
        if (processed > 0 && seconds > 0) {
            round.sample(step.name() + "_perSec", processed / seconds);
        }
        round.sample(step.name() + "_hibernateStatements", statistics.getPrepareStatementCount());
        round.sample(step.name() + "_dbStatements", dbStatements);
        if (step.targets() > 0) {
            round.sample(step.name() + "_dbStatementsPerTarget", (double) dbStatements / step.targets());
        }
        round.sample(step.name() + "_heapPeakDeltaMb", Math.max(0, heapPeak - heapBefore) / (double) MIB);
        if (step.name().equals(PREPARE)) {
            int skipped = logs.skippedCampaigns();
            int rows = processed + skipped;
            round.sample("prepare_chunks", Math.ceil((double) rows / CHUNK_SIZE));
            round.sample("prepare_retriedChunks", logs.retriedChunks());
            round.sample("prepare_skippedCampaigns", skipped);
        }
        return processed;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /*
     * 서버가 실행한 SELECT, INSERT, UPDATE, DELETE 의 수다. Hibernate 통계는 JdbcTemplate 으로 실행하는 가게 요약 저장을
     * 세지 못해서 서버 쪽 카운터를 함께 읽는다. 이 조회 자신도 한 건씩 세지만 그 차이는 단계의 수천 건에 비해 무시한다.
     */
    private long dbStatements() {
        long total = 0;
        for (long value : jdbc.query(
                "show global status where variable_name in ('Com_select', 'Com_insert', 'Com_update', 'Com_delete')",
                (rs, row) -> Long.parseLong(rs.getString(2)))) {
            total += value;
        }
        return total;
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%s%%", stripZeros(rate * 100));
    }

    private static String stripZeros(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /*
     * 재는 동안 한 건마다 쓰는 로그를 줄이고, 가게 요약 준비 단계가 남기는 로그로 한 건씩 다시 저장한 청크 수를 센다.
     * 닫으면 바꾼 레벨을 모두 원래대로 돌려놓는다.
     */
    private static final class LogControl implements AutoCloseable {

        private static final String SUMMARY_LOGGER = "com.lunchcatch.campaign.service.CampaignStoreSummaryService";
        private static final String TRANSITION_LOGGER = "com.lunchcatch.campaign.service.CampaignDailyTransitionService";

        private final Logger root;
        private final Logger summary;
        private final Logger transition;
        private final Level rootLevel;
        private final Level summaryLevel;
        private final Level transitionLevel;
        private final boolean summaryAdditive;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        LogControl() {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            summary = context.getLogger(SUMMARY_LOGGER);
            transition = context.getLogger(TRANSITION_LOGGER);
            rootLevel = root.getLevel();
            summaryLevel = summary.getLevel();
            transitionLevel = transition.getLevel();
            summaryAdditive = summary.isAdditive();

            root.setLevel(Level.WARN);
            // 건너뛴 캠페인마다 남는 ERROR 로그는 처리 건수로 알 수 있어서 끈다.
            transition.setLevel(Level.OFF);
            // 가게 요약 준비의 WARN(청크 재시도)과 ERROR(건너뜀)는 세어야 하므로 이 로거만 남기고 콘솔로는 보내지 않는다.
            summary.setLevel(Level.WARN);
            summary.setAdditive(false);
            appender.setContext(context);
            appender.start();
            summary.addAppender(appender);
        }

        void clear() {
            appender.list.clear();
        }

        int retriedChunks() {
            return countContaining("CAMPAIGN_DAILY_SUMMARY_CHUNK_RETRY");
        }

        int skippedCampaigns() {
            return countContaining("CAMPAIGN_DAILY_SUMMARY_SKIPPED");
        }

        private int countContaining(String event) {
            return (int) List.copyOf(appender.list).stream()
                    .filter(logEvent -> logEvent.getFormattedMessage().contains(event))
                    .count();
        }

        @Override
        public void close() {
            summary.detachAppender(appender);
            appender.stop();
            summary.setAdditive(summaryAdditive);
            summary.setLevel(summaryLevel);
            transition.setLevel(transitionLevel);
            root.setLevel(rootLevel);
        }
    }

    // 단계가 도는 동안 사용 중인 힙의 최댓값을 짧은 간격으로 읽어 둔다. 정확한 최고점이 아니라 근삿값이다.
    private static final class HeapSampler implements AutoCloseable {

        private static final long INTERVAL_MILLIS = 5;

        private volatile boolean running = true;
        private volatile long peak = usedHeap();
        private final Thread thread = new Thread(() -> {
            while (running) {
                peak = Math.max(peak, usedHeap());
                try {
                    Thread.sleep(INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "heap-sampler");

        HeapSampler() {
            thread.setDaemon(true);
            thread.start();
        }

        long peak() {
            return Math.max(peak, usedHeap());
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }
}
