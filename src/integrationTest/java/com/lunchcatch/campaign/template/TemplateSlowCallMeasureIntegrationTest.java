package com.lunchcatch.campaign.template;

import com.lunchcatch.auth.AuthCookieNames;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.campaign.AbstractCampaignIntegrationTest;
import com.lunchcatch.campaign.template.SlowCallMeasureSettings.Mode;
import com.lunchcatch.global.measure.ScenarioMeasure;
import com.lunchcatch.global.measure.ScenarioMeasure.Condition;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/*
 * 느린 모델 호출이 겹칠 때 동시 호출 제한이 정한 대로 거절하는지, 그동안 다른 API 가 얼마나 느려지는지 잰다(#1269).
 * 질문과 통과 기준은 docs/design/campaign/loadtest/템플릿_생성_느린_호출_부하_시험_결과.md 1절이 갖는다.
 *
 * 회차마다 기준 구간과 생성 구간을 차례로 둔다. 기준 구간은 생성 요청 없이 생성 구간과 같은 시간만큼 기다린다. 두 구간
 * 내내 다른 API(관리자 템플릿 목록 조회, 관리자 로그인)를 일정한 간격으로 부르고, 호출을 시작한 구간으로 응답 시간을
 * 가른다. 생성 구간에서는 톰캣 스레드와 벌크헤드 지표를 짧은 간격으로 읽어 최대를 남긴다.
 *
 * 다른 API 의 응답 시간은 기록만 하고 단언하지 않는다. 300ms 기준의 판정은 결과 문서가 한다. 한 대의 PC 에서 도는
 * 값이라 ./gradlew check 에서 단언하면 측정과 관계없는 흔들림으로 실패한다.
 *
 * 공용 컨텍스트가 앱을 실제 포트로 띄우고 실제 생성기에 Bedrock 클라이언트 스텁을 꽂는다. 이 테스트는 HTTP 로 부르고
 * 스텁의 지연과 조건만 바꾼다. 끝나면 스텁을 기본(지연 없음)으로 되돌려 같은 컨텍스트를 쓰는 다른 테스트에 남기지 않는다.
 *
 * 요청이 다른 스레드와 다른 연결에서 돌아 저장한 행을 봐야 하므로 공용 컨텍스트의 클래스 단위 트랜잭션을 끈다.
 *
 * 관리자 1명당 분당 3회 제한에 걸리지 않게 회차마다 새 관리자 8명을 쓴다. 토큰은 로그인 API 를 거치지 않고 직접
 * 발급한다. 로그인 API 는 IP 1개당 분당 10회로 막혀 있어 측정용 로그인 호출만 그 한도 안에서 쓴다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TemplateSlowCallMeasureIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String PATH = "/v1/admin/templates";
    private static final int ADMINS = 8;
    private static final int BULKHEAD_LIMIT = 5;
    private static final long REJECT_WITHIN_MILLIS = 1_000L;
    private static final String THROTTLED = "POSTER-012";
    private static final String BULKHEAD_REJECTED_METRIC = "campaign_bedrock_bulkhead_rejected_total";
    private static final String BULKHEAD_AVAILABLE_METRIC = "resilience4j.bulkhead.available.concurrent.calls";
    private static final String TOMCAT_BUSY_METRIC = "tomcat.threads.busy";
    private static final String TOMCAT_QUEUED_METRIC = "tomcat.threads.queued";
    private static final int TOMCAT_MAX_THREADS = 200;

    private static final String LIST_API = "목록 조회";
    private static final String LOGIN_API = "로그인";
    private static final String LOGIN_PATH = "/v1/admin/auth/tokens";
    private static final String LOGIN_PASSWORD = "Measure!2026";
    private static final long LIST_PROBE_INTERVAL_MILLIS = 200L;
    // 로그인은 IP 1개당 분당 10회로 막혀 있다. 6.5초 간격이면 어느 1분에도 10번을 넘지 않는다.
    private static final long LOGIN_PROBE_INTERVAL_MILLIS = 6_500L;
    private static final long PROBE_TIMEOUT_MILLIS = 5_000L;
    private static final long GAUGE_SAMPLE_INTERVAL_MILLIS = 20L;
    private static final String BASELINE = "기준";
    private static final String GENERATION = "생성 중";

    private static final SlowCallMeasureSettings SETTINGS = SlowCallMeasureSettings.fromEnvironment();

    @LocalServerPort
    private int port;
    @Autowired
    private JwtTokenProvider jwt;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(ADMINS);
    private final List<Long> createdAdminIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();
    private final AtomicInteger requestSequence = new AtomicInteger();

    private final ScheduledExecutorService probes = Executors.newScheduledThreadPool(3);
    private final Queue<ProbeSample> probeSamples = new ConcurrentLinkedQueue<>();
    private final List<RoundWindows> roundWindows = new ArrayList<>();
    private final AtomicInteger windowSequence = new AtomicInteger();
    private volatile Window window;
    private final AtomicInteger busyMax = new AtomicInteger();
    private final AtomicInteger queuedMax = new AtomicInteger();
    private final AtomicInteger bulkheadUsedMax = new AtomicInteger();
    private Gauge busyGauge;
    private Gauge queuedGauge;
    private Gauge bulkheadAvailableGauge;
    private String probeToken;
    private String probeLoginId;

    @AfterEach
    void tearDown() {
        BEDROCK_STUB.reset(0L, Mode.SINGLE);
        probes.shutdownNow();
        executor.shutdownNow();
        http.close();
        deleteCreatedTemplates();
        createdAdminIds.forEach(adminId -> {
            jdbc.update("delete from admin_login_session where admin_id = ?", adminId);
            jdbc.update("delete from admin where admin_id = ?", adminId);
        });
    }

    @Test
    void 관리자_8명이_동시에_만들면_모델은_5개만_부르고_나머지는_바로_거절한다() throws Exception {
        ScenarioMeasure measure = new ScenarioMeasure("template-slow-call");
        findGauges();
        probeToken = newAdminToken();
        probeLoginId = newLoginAdmin();

        // 첫 요청은 클래스 로딩과 연결 준비가 섞여 늦다. 회차보다 먼저 한 번 보내 그 시간이 회차에 섞이지 않게 한다.
        BEDROCK_STUB.reset(SETTINGS.delayMillis(), Mode.SINGLE);
        send(newAdminToken());
        deleteCreatedTemplates();

        probes.scheduleAtFixedRate(this::probeList, 0, LIST_PROBE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        probes.scheduleAtFixedRate(this::probeLogin, 0, LOGIN_PROBE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        probes.scheduleAtFixedRate(this::sampleGauges, 0, GAUGE_SAMPLE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

        for (Mode mode : SETTINGS.modes()) {
            Condition condition = measure.condition(mode.name() + ", 지연 " + SETTINGS.delayMillis() + "ms",
                    "Q1-1", "Q1-2", "Q1-3", "Q1-4", "Q3-1", "Q3-2");
            for (int i = 0; i < SETTINGS.rounds(); i++) {
                runRound(condition, mode);
                deleteCreatedTemplates();
            }
        }

        // 구간이 끝날 때 아직 돌던 다른 API 호출까지 기다린 뒤 회차에 나눠 담는다.
        probes.shutdown();
        probes.awaitTermination(PROBE_TIMEOUT_MILLIS + 1_000L, TimeUnit.MILLISECONDS);
        roundWindows.forEach(this::recordProbes);

        measure.finish();
    }

    private void runRound(Condition condition, Mode mode) throws Exception {
        BEDROCK_STUB.reset(SETTINGS.delayMillis(), mode);
        double rejectedBefore = bulkheadRejected();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < ADMINS; i++) {
            tokens.add(newAdminToken());
        }

        // 기준 구간은 생성 구간과 같은 시간만큼 둔다. 생성 구간은 요청 하나가 모델을 부르는 횟수만큼 지연이 쌓인다.
        int callsPerRequest = mode == Mode.CORRECTION_RETRY ? 2 : 1;
        Window baseline = new Window(windowSequence.incrementAndGet(), BASELINE);
        window = baseline;
        Thread.sleep(callsPerRequest * SETTINGS.delayMillis());

        Window generation = new Window(windowSequence.incrementAndGet(), GENERATION);
        busyMax.set(0);
        queuedMax.set(0);
        bulkheadUsedMax.set(0);
        window = generation;

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        for (String token : tokens) {
            futures.add(executor.submit(() -> {
                start.await();
                return send(token);
            }));
        }
        start.countDown();
        List<Result> results = new ArrayList<>();
        try {
            for (Future<Result> future : futures) {
                results.add(future.get());
            }
        } finally {
            // 한 요청이 실패해도 나머지 요청이 끝난 뒤에 tearDown 이 스텁과 데이터를 정리하게 한다.
            awaitAll(futures);
        }
        window = null;

        ScenarioMeasure.Condition.Round round = condition.startRound();
        roundWindows.add(new RoundWindows(round, baseline, generation));
        List<Result> succeeded = results.stream().filter(result -> result.status() == 201).toList();
        List<Result> throttled = results.stream().filter(result -> THROTTLED.equals(result.code())).toList();
        long slowestRejectMillis = throttled.stream().mapToLong(Result::elapsedMillis).max().orElse(0L);
        Set<String> firstCallCompleted = BEDROCK_STUB.firstCallCompletedPrompts();

        round.check("Q1-1", succeeded.size() == BULKHEAD_LIMIT, "성공 " + succeeded.size() + "건, 응답 " + codes(results));
        round.check("Q1-2", throttled.size() == ADMINS - BULKHEAD_LIMIT,
                THROTTLED + " " + throttled.size() + "건, 응답 " + codes(results));
        round.check("Q1-3", BEDROCK_STUB.maxInFlight() <= BULKHEAD_LIMIT, "동시 진행 최대 " + BEDROCK_STUB.maxInFlight());
        round.check("Q1-4", slowestRejectMillis <= REJECT_WITHIN_MILLIS, "가장 늦은 거절 " + slowestRejectMillis + "ms");
        round.check("Q3-1", busyMax.get() < TOMCAT_MAX_THREADS, "바쁜 스레드 최대 " + busyMax.get());

        round.count("모델 호출", BEDROCK_STUB.calls());
        round.count("벌크헤드 거절", Math.round(bulkheadRejected() - rejectedBefore));
        round.count("첫 호출 뒤 두 번째 호출 거절",
                throttled.stream().filter(result -> firstCallCompleted.contains(result.prompt())).count());
        round.sample("동시 진행 최대", BEDROCK_STUB.maxInFlight());
        round.sample("벌크헤드 사용 최대", bulkheadUsedMax.get());
        round.sample("바쁜 스레드 최대", busyMax.get());
        // 톰캣은 쉬는 스레드가 있어도 요청을 대기열에 넣고 꺼내 가서 이 값이 잠깐 1이 된다. 고갈의 판정으로 쓰지 않고 기록만 한다.
        round.sample("작업 스레드를 기다린 요청 최대", queuedMax.get());
        throttled.forEach(result -> round.sample("거절 응답 ms", result.elapsedMillis()));
        succeeded.forEach(result -> round.sample("성공 응답 ms", result.elapsedMillis()));
    }

    private static void awaitAll(List<Future<Result>> futures) {
        for (Future<Result> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                // 실패는 위에서 이미 던졌거나 던질 것이다. 여기서는 끝나기만 기다린다.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Result send(String token) throws Exception {
        String requestId = UUID.randomUUID().toString();
        // 문구에 UUID 를 넣으면 숫자 줄이 전화번호 패턴에 걸려 POSTER-025 로 거절된다. 짧은 순번으로 요청을 가른다.
        String prompt = "따뜻한 느낌의 카드형 템플릿을 만들어줘 (측정 요청 " + requestSequence.incrementAndGet() + ")";
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "느린 호출 측정",
                "requestPrompt", prompt,
                "requestId", requestId));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .timeout(Duration.ofMillis(2 * SETTINGS.delayMillis() + 10_000L))
                .header("Content-Type", "application/json")
                .header("Cookie", AuthCookieNames.accessTokenCookieNameForPath(PATH) + "=" + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long startNanos = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        JsonNode json = objectMapper.readTree(response.body());
        if (response.statusCode() == 201) {
            synchronized (createdTemplateIds) {
                createdTemplateIds.add(json.path("data").path("templateId").asLong());
            }
        }
        return new Result(prompt, response.statusCode(), json.path("code").asString(), elapsedMillis);
    }

    /*
     * 기준 구간과 생성 구간에 시작한 다른 API 호출을 회차에 담는다. Q3-2 는 생성 구간에서 시간 초과로 끝난 호출이 없는지 본다.
     * 실패 응답(2xx 가 아닌 응답)은 측정이 다른 이유로 막혔다는 뜻이라 따로 센다.
     *
     * 표본은 정상 응답(2xx)을 받은 호출만 센다. 실패 응답과 시간 초과만 있는 구간은 응답 시간을 잰 것이 아니다.
     * 200ms 간격인 목록 조회는 표본이 없으면 Q3-2 를 실패로 하고, 6.5초 간격인 로그인은 지연이 짧으면 표본이 없을 수 있어
     * 실패로 하지 않고 표본 수와 함께 판정 불충분으로 적는다.
     */
    private void recordProbes(RoundWindows windows) {
        long timedOut = 0;
        Map<String, Long> generationSamples = new HashMap<>();
        for (ProbeSample sample : probeSamples) {
            boolean inGeneration = sample.window().equals(windows.generation());
            if (!inGeneration && !sample.window().equals(windows.baseline())) {
                continue;
            }
            if (inGeneration && sample.succeeded()) {
                generationSamples.merge(sample.api(), 1L, Long::sum);
            }
            String name = sample.api() + " " + sample.window().phase();
            if (sample.succeeded()) {
                windows.round().sample(name + " ms", sample.elapsedMillis());
            } else if (sample.timedOut()) {
                timedOut += inGeneration ? 1 : 0;
                windows.round().count(name + " 시간 초과");
            } else {
                windows.round().count(name + " 실패 응답 " + sample.status());
            }
        }
        long listSamples = generationSamples.getOrDefault(LIST_API, 0L);
        long loginSamples = generationSamples.getOrDefault(LOGIN_API, 0L);
        windows.round().count(LIST_API + " 생성 중 표본", listSamples);
        windows.round().count(LOGIN_API + " 생성 중 표본", loginSamples);
        String detail = "생성 구간의 다른 API 시간 초과 " + timedOut + "건, 표본 " + LIST_API + " " + listSamples
                + "개 " + LOGIN_API + " " + loginSamples + "개" + (loginSamples == 0 ? " (로그인은 판정 불충분)" : "");
        windows.round().check("Q3-2", timedOut == 0 && listSamples > 0, detail);
    }

    private void probeList() {
        probe(LIST_API, HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Cookie", AuthCookieNames.accessTokenCookieNameForPath(PATH) + "=" + probeToken)
                .GET());
    }

    private void probeLogin() {
        String body = objectMapper.writeValueAsString(Map.of("loginId", probeLoginId, "password", LOGIN_PASSWORD));
        probe(LOGIN_API, HttpRequest.newBuilder(URI.create("http://localhost:" + port + LOGIN_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    // 예약 실행기는 예외로 끝난 작업을 다시 돌리지 않는다. 실패도 표본으로 남기고 예외를 밖으로 던지지 않는다.
    private void probe(String api, HttpRequest.Builder builder) {
        Window started = window;
        if (started == null) {
            return;
        }
        HttpRequest request = builder.timeout(Duration.ofMillis(PROBE_TIMEOUT_MILLIS)).build();
        long startNanos = System.nanoTime();
        int status = 0;
        boolean timedOut = false;
        try {
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (HttpTimeoutException e) {
            timedOut = true;
        } catch (IOException e) {
            // 연결 실패는 상태 코드 0 의 실패 응답으로 센다.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        probeSamples.add(new ProbeSample(started, api, status, timedOut, elapsedMillis));
    }

    private void sampleGauges() {
        Window current = window;
        if (current == null || !GENERATION.equals(current.phase())) {
            return;
        }
        busyMax.accumulateAndGet((int) busyGauge.value(), Math::max);
        queuedMax.accumulateAndGet((int) queuedGauge.value(), Math::max);
        bulkheadUsedMax.accumulateAndGet(BULKHEAD_LIMIT - (int) bulkheadAvailableGauge.value(), Math::max);
    }

    /*
     * 대기 요청 지표(TomcatQueuedRequestsMetrics)는 요청을 받는 서버에만 붙으므로, 그 꼬리표로 같은 서버의 바쁜 스레드 지표를 고른다.
     * 임의 포트로 뜨면 대기 요청 꼬리표 뒤에만 포트가 붙어(http-nio-auto-1 과 http-nio-auto-1-61683) 앞부분이 같은지로 가른다.
     */
    private void findGauges() {
        queuedGauge = Objects.requireNonNull(meterRegistry.find(TOMCAT_QUEUED_METRIC).gauge(), TOMCAT_QUEUED_METRIC);
        String connector = queuedGauge.getId().getTag("name");
        busyGauge = meterRegistry.find(TOMCAT_BUSY_METRIC).gauges().stream()
                .filter(gauge -> connector.startsWith(gauge.getId().getTag("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(TOMCAT_BUSY_METRIC + " 가 " + connector + " 서버에 없다"));
        bulkheadAvailableGauge = Objects.requireNonNull(
                meterRegistry.find(BULKHEAD_AVAILABLE_METRIC).tag("name", "bedrock").gauge(), BULKHEAD_AVAILABLE_METRIC);
    }

    // 로그인 API 를 부를 관리자다. 실제 비밀번호 해시가 있어야 로그인이 해시 검증까지 간다.
    private String newLoginAdmin() {
        // 아이디는 50자를 넘을 수 없다. UUID 36자에 짧은 접두어만 붙인다.
        String loginId = "slow-login-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, passwordEncoder.encode(LOGIN_PASSWORD), "slow-call-measure-admin", Role.ADMIN.name(), now, now);
        createdAdminIds.add(jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId));
        return loginId;
    }

    // 계정 행이 있어야 관리자 토큰이 유효하다(AdminAccessTokenVerifier).
    private String newAdminToken() {
        String loginId = "slow-call-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, "test-hash", "slow-call-measure-admin", Role.ADMIN.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return jwt.createAccessToken(adminId, Role.ADMIN);
    }

    /*
     * 전체 템플릿 수에 상한(100개)이 있어 회차마다 만든 템플릿을 지운다. 지우지 않으면 반복 횟수를 늘린 측정이 상한에 걸린다.
     * 저장할 때 template_quota 를 하나씩 늘렸으므로 지운 만큼 되돌린다.
     *
     * 한 트랜잭션으로 지운다. 버전을 지운 뒤 템플릿을 지우기 전에 목록 조회가 끼어들면 버전 없는 템플릿을 보고 500 을 낸다.
     * 운영의 삭제(TemplateDeleteService)도 둘을 한 트랜잭션에서 지운다.
     */
    private void deleteCreatedTemplates() {
        synchronized (createdTemplateIds) {
            transactionTemplate.executeWithoutResult(status -> {
                for (Long templateId : createdTemplateIds) {
                    jdbc.update("delete from template_version where template_id = ?", templateId);
                    jdbc.update("delete from template where template_id = ?", templateId);
                }
                if (!createdTemplateIds.isEmpty()) {
                    jdbc.update("update template_quota set current_count = current_count - ?", createdTemplateIds.size());
                }
            });
            createdTemplateIds.clear();
        }
    }

    private double bulkheadRejected() {
        return meterRegistry.get(BULKHEAD_REJECTED_METRIC).counter().count();
    }

    private static String codes(List<Result> results) {
        return results.stream().map(result -> result.status() + " " + result.code()).toList().toString();
    }

    private record Result(String prompt, int status, String code, long elapsedMillis) {
    }

    private record Window(int id, String phase) {
    }

    private record RoundWindows(ScenarioMeasure.Condition.Round round, Window baseline, Window generation) {
    }

    private record ProbeSample(Window window, String api, int status, boolean timedOut, long elapsedMillis) {

        boolean succeeded() {
            return status >= 200 && status < 300;
        }
    }
}
