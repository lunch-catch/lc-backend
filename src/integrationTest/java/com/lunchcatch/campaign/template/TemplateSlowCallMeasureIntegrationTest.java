package com.lunchcatch.campaign.template;

import com.lunchcatch.auth.AuthCookieNames;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.campaign.AbstractCampaignIntegrationTest;
import com.lunchcatch.campaign.template.SlowCallMeasureSettings.Mode;
import com.lunchcatch.global.measure.ScenarioMeasure;
import com.lunchcatch.global.measure.ScenarioMeasure.Condition;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/*
 * 느린 모델 호출이 겹칠 때 동시 호출 제한이 정한 대로 거절하는지 잰다(#1269). 질문과 통과 기준은
 * docs/design/campaign/loadtest/템플릿_생성_느린_호출_부하_시험_결과.md 1절이 갖는다.
 *
 * 공용 컨텍스트가 앱을 실제 포트로 띄우고 실제 생성기에 Bedrock 클라이언트 스텁을 꽂는다. 이 테스트는 HTTP 로 부르고
 * 스텁의 지연과 조건만 바꾼다. 끝나면 스텁을 기본(지연 없음)으로 되돌려 같은 컨텍스트를 쓰는 다른 테스트에 남기지 않는다.
 *
 * 요청이 다른 스레드와 다른 연결에서 돌아 저장한 행을 봐야 하므로 공용 컨텍스트의 클래스 단위 트랜잭션을 끈다.
 *
 * 관리자 1명당 분당 3회 제한에 걸리지 않게 회차마다 새 관리자 8명을 쓴다. 토큰은 로그인 API 를 거치지 않고 직접
 * 발급한다. 로그인 API 는 IP 1개당 분당 10회로 막혀 있다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TemplateSlowCallMeasureIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String PATH = "/v1/admin/templates";
    private static final int ADMINS = 8;
    private static final int BULKHEAD_LIMIT = 5;
    private static final long REJECT_WITHIN_MILLIS = 1_000L;
    private static final String THROTTLED = "POSTER-012";
    private static final String BULKHEAD_REJECTED_METRIC = "campaign_bedrock_bulkhead_rejected_total";

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

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(ADMINS);
    private final List<Long> createdAdminIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();
    private final AtomicInteger requestSequence = new AtomicInteger();

    @AfterEach
    void tearDown() {
        BEDROCK_STUB.reset(0L, Mode.SINGLE);
        executor.shutdownNow();
        http.close();
        deleteCreatedTemplates();
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
    }

    @Test
    void 관리자_8명이_동시에_만들면_모델은_5개만_부르고_나머지는_바로_거절한다() throws Exception {
        ScenarioMeasure measure = new ScenarioMeasure("template-slow-call");

        // 첫 요청은 클래스 로딩과 연결 준비가 섞여 늦다. 회차보다 먼저 한 번 보내 그 시간이 회차에 섞이지 않게 한다.
        BEDROCK_STUB.reset(SETTINGS.delayMillis(), Mode.SINGLE);
        send(newAdminToken());
        deleteCreatedTemplates();

        for (Mode mode : SETTINGS.modes()) {
            Condition condition = measure.condition(
                    mode.name() + ", 지연 " + SETTINGS.delayMillis() + "ms", "Q1-1", "Q1-2", "Q1-3", "Q1-4");
            for (int i = 0; i < SETTINGS.rounds(); i++) {
                runRound(condition, mode);
                deleteCreatedTemplates();
            }
        }

        measure.finish();
    }

    private void runRound(Condition condition, Mode mode) throws Exception {
        BEDROCK_STUB.reset(SETTINGS.delayMillis(), mode);
        double rejectedBefore = bulkheadRejected();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < ADMINS; i++) {
            tokens.add(newAdminToken());
        }

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
        for (Future<Result> future : futures) {
            results.add(future.get());
        }

        ScenarioMeasure.Condition.Round round = condition.startRound();
        List<Result> succeeded = results.stream().filter(result -> result.status() == 201).toList();
        List<Result> throttled = results.stream().filter(result -> THROTTLED.equals(result.code())).toList();
        long slowestRejectMillis = throttled.stream().mapToLong(Result::elapsedMillis).max().orElse(0L);
        Set<String> firstCallCompleted = BEDROCK_STUB.firstCallCompletedPrompts();

        round.check("Q1-1", succeeded.size() == BULKHEAD_LIMIT, "성공 " + succeeded.size() + "건, 응답 " + codes(results));
        round.check("Q1-2", throttled.size() == ADMINS - BULKHEAD_LIMIT,
                THROTTLED + " " + throttled.size() + "건, 응답 " + codes(results));
        round.check("Q1-3", BEDROCK_STUB.maxInFlight() <= BULKHEAD_LIMIT, "동시 진행 최대 " + BEDROCK_STUB.maxInFlight());
        round.check("Q1-4", slowestRejectMillis <= REJECT_WITHIN_MILLIS, "가장 늦은 거절 " + slowestRejectMillis + "ms");

        round.count("모델 호출", BEDROCK_STUB.calls());
        round.count("벌크헤드 거절", Math.round(bulkheadRejected() - rejectedBefore));
        round.count("첫 호출 뒤 두 번째 호출 거절",
                throttled.stream().filter(result -> firstCallCompleted.contains(result.prompt())).count());
        round.sample("동시 진행 최대", BEDROCK_STUB.maxInFlight());
        throttled.forEach(result -> round.sample("거절 응답 ms", result.elapsedMillis()));
        succeeded.forEach(result -> round.sample("성공 응답 ms", result.elapsedMillis()));
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
     */
    private void deleteCreatedTemplates() {
        synchronized (createdTemplateIds) {
            for (Long templateId : createdTemplateIds) {
                jdbc.update("delete from template_version where template_id = ?", templateId);
                jdbc.update("delete from template where template_id = ?", templateId);
            }
            if (!createdTemplateIds.isEmpty()) {
                jdbc.update("update template_quota set current_count = current_count - ?", createdTemplateIds.size());
            }
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
}
