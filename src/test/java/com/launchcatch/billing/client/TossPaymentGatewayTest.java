package com.launchcatch.billing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.billing.config.TossCircuitBreakerConfig;
import com.launchcatch.billing.config.TossClientConfig;
import com.launchcatch.billing.config.TossProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/*
 * 실패 분류를 가장 많이 본다. Fake 서버가 실제 소켓으로 응답을 만들어 주므로 연결 거부와 읽기 타임아웃도
 * 진짜 전송 단계 예외로 재현된다. DNS 실패만은 시험 환경의 DNS 에 기대지 않으려고 예외를 직접 만들어 흘린다.
 */
class TossPaymentGatewayTest {

    private static final String SECRET = "test_sk_SECRET_VALUE_1234";
    private static final String PAYMENT_KEY = "pk_abc";
    private static final String ORDER_ID = "ORDER-20261010-0001";
    private static final long AMOUNT = 50_000L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String DONE_BODY = """
            {"paymentKey":"pk_abc","orderId":"ORDER-20261010-0001","status":"DONE",
             "totalAmount":50000,"approvedAt":"2026-10-10T07:00:00+00:00","method":"카드"}""";

    private FakeTossServer server;
    private CircuitBreaker circuitBreaker;
    private TossPaymentGateway gateway;
    private ListAppender<ILoggingEvent> logs;
    private Logger gatewayLogger;

    @BeforeEach
    void setUp() {
        server = FakeTossServer.start();
        circuitBreaker = newCircuitBreaker();
        gateway = new TossPaymentGateway(webClientTo(server.baseUrl()), circuitBreaker, Clock.system(SEOUL), SECRET);

        logs = new ListAppender<>();
        logs.start();
        gatewayLogger = (Logger) LoggerFactory.getLogger(TossPaymentGateway.class);
        gatewayLogger.setLevel(Level.DEBUG);
        gatewayLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        gatewayLogger.detachAppender(logs);
        server.close();
    }

    private static CircuitBreaker newCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .recordException(TossCircuitBreakerConfig::isCircuitFailure)
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("tossPaymentTest");
    }

    private static WebClient webClientTo(String baseUrl) {
        TossProperties properties = new TossProperties(baseUrl, SECRET, Duration.ofMillis(500), Duration.ofMillis(300));
        return new TossClientConfig().tossPaymentWebClient(WebClient.builder(), properties);
    }

    // --- 승인 ----------------------------------------------------------------------------

    @Test
    void 승인에_성공하면_결제키_주문번호_금액_승인시각을_돌려준다() {
        server.reply(200, DONE_BODY);

        PaymentGatewayApproval approval = gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(approval.paymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(approval.orderId()).isEqualTo(ORDER_ID);
        assertThat(approval.amount()).isEqualTo(AMOUNT);
        assertThat(approval.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 10, 16, 0));
    }

    @Test
    void 승인_요청은_비밀키_인증과_멱등키를_달고_세_필드를_보낸다() {
        server.reply(200, DONE_BODY);

        gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);

        FakeTossServer.Recorded request = server.requests().get(0);
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/payments/confirm");
        assertThat(request.authorization()).isEqualTo(expectedAuth);
        assertThat(request.idempotencyKey()).isEqualTo(PAYMENT_KEY);
        assertThat(request.body())
                .contains("\"paymentKey\":\"pk_abc\"")
                .contains("\"orderId\":\"ORDER-20261010-0001\"")
                .contains("\"amount\":50000");
    }

    @Test
    void 카드사가_거절하면_PgRejectedException_이고_토스_코드와_사유를_담는다() {
        server.reply(400, """
                {"code":"REJECT_CARD_COMPANY","message":"카드사에서 거절했습니다."}""");

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOfSatisfying(PgRejectedException.class, e -> {
                    assertThat(e.getPgCode()).isEqualTo("REJECT_CARD_COMPANY");
                    assertThat(e.getMessage()).isEqualTo("카드사에서 거절했습니다.");
                });
    }

    @Test
    void 거절_목록에_없는_4xx_코드는_PgUnknownException_이다() {
        server.reply(409, """
                {"code":"ALREADY_PROCESSED_PAYMENT","message":"이미 처리된 결제입니다."}""");

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgUnknownException.class);
    }

    @Test
    void 처음_보는_오류_코드도_거절로_단정하지_않고_PgUnknownException_이다() {
        server.reply(400, """
                {"code":"SOME_NEW_TOSS_CODE","message":"새 코드"}""");

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgUnknownException.class);
    }

    @Test
    void 서버_오류_5xx_는_PgUnknownException_이다() {
        server.reply(500, """
                {"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"내부 시스템 처리 작업이 실패했습니다."}""");

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgUnknownException.class);
    }

    @Test
    void 응답이_읽기_타임아웃을_넘기면_PgUnknownException_이다() {
        server.replyAfter(1_500, 200, DONE_BODY);

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgUnknownException.class);
    }

    @Test
    void 연결이_거부되면_PgNotSentException_이다() {
        FakeTossServer closed = FakeTossServer.start();
        String deadUrl = closed.baseUrl();
        closed.close();
        TossPaymentGateway toDeadServer =
                new TossPaymentGateway(webClientTo(deadUrl), newCircuitBreaker(), Clock.system(SEOUL), SECRET);

        assertThatThrownBy(() -> toDeadServer.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgNotSentException.class);
    }

    @Test
    void DNS_조회에_실패하면_PgNotSentException_이다() {
        WebClient dnsFailure = WebClient.builder()
                .exchangeFunction(request -> Mono.<ClientResponse>error(new WebClientRequestException(
                        new UnknownHostException("api.tosspayments.com"), HttpMethod.POST,
                        URI.create("https://api.tosspayments.com/v1/payments/confirm"), HttpHeaders.EMPTY)))
                .build();
        TossPaymentGateway gatewayWithoutDns =
                new TossPaymentGateway(dnsFailure, newCircuitBreaker(), Clock.system(SEOUL), SECRET);

        assertThatThrownBy(() -> gatewayWithoutDns.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgNotSentException.class);
    }

    @Test
    void 서킷이_열리면_PgNotSentException_이고_요청이_토스로_나가지_않는다() {
        server.reply(503, "{}");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .isInstanceOf(PgUnknownException.class);
        }
        int sentBeforeOpen = server.requests().size();

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgNotSentException.class);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(server.requests()).hasSize(sentBeforeOpen);
    }

    @Test
    void 거절_응답_4xx_가_쌓여도_서킷은_열리지_않는다() {
        server.reply(400, """
                {"code":"REJECT_CARD_COMPANY","message":"거절"}""");
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .isInstanceOf(PgRejectedException.class);
        }
        server.reply(200, DONE_BODY);

        PaymentGatewayApproval approval = gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT);

        assertThat(approval.orderId()).isEqualTo(ORDER_ID);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 승인_응답의_상태가_DONE_이_아니면_PgUnknownException_이다() {
        server.reply(200, """
                {"paymentKey":"pk_abc","orderId":"ORDER-20261010-0001","status":"WAITING_FOR_DEPOSIT",
                 "totalAmount":50000}""");

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgUnknownException.class)
                .hasMessageContaining("WAITING_FOR_DEPOSIT");
    }

    @Test
    void 승인_응답_본문을_읽을_수_없으면_PgUnknownException_이다() {
        server.reply(200, "이건 JSON 이 아니다");

        assertThatThrownBy(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(PgUnknownException.class);
    }

    // --- 조회 ----------------------------------------------------------------------------

    @Test
    void 주문번호로_조회하면_승인_상태와_주문번호_금액을_돌려준다() {
        server.reply(200, DONE_BODY);

        PaymentGatewayInquiryResult result = gateway.inquireByOrderId(ORDER_ID);

        assertThat(result.status()).isEqualTo(PaymentGatewayInquiryResult.Status.APPROVED);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.paymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(result.amount()).isEqualTo(AMOUNT);
        assertThat(result.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 10, 16, 0));
        assertThat(server.requests().get(0).method()).isEqualTo("GET");
        assertThat(server.requests().get(0).path()).isEqualTo("/v1/payments/orders/" + ORDER_ID);
    }

    @Test
    void 결제키로_조회하면_승인_상태와_주문번호_금액을_돌려준다() {
        server.reply(200, DONE_BODY);

        PaymentGatewayInquiryResult result = gateway.inquireByPaymentKey(PAYMENT_KEY);

        assertThat(result.status()).isEqualTo(PaymentGatewayInquiryResult.Status.APPROVED);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.amount()).isEqualTo(AMOUNT);
        assertThat(server.requests().get(0).path()).isEqualTo("/v1/payments/" + PAYMENT_KEY);
    }

    @ParameterizedTest
    @CsvSource({
            "DONE, APPROVED",
            "CANCELED, CANCELED",
            "PARTIAL_CANCELED, CANCELED",
            "ABORTED, FAILED",
            "EXPIRED, FAILED",
            "READY, PENDING",
            "IN_PROGRESS, PENDING",
            "WAITING_FOR_DEPOSIT, PENDING",
            "A_STATUS_TOSS_ADDS_LATER, PENDING"
    })
    void 토스_상태를_우리_상태로_옮긴다(String tossStatus, PaymentGatewayInquiryResult.Status expected) {
        server.reply(200, """
                {"paymentKey":"pk_abc","orderId":"ORDER-20261010-0001","status":"%s","totalAmount":50000}"""
                .formatted(tossStatus));

        PaymentGatewayInquiryResult result = gateway.inquireByOrderId(ORDER_ID);

        assertThat(result.status()).isEqualTo(expected);
    }

    @Test
    void 실패한_결제를_조회하면_실패_코드와_사유를_담는다() {
        server.reply(200, """
                {"paymentKey":"pk_abc","orderId":"ORDER-20261010-0001","status":"ABORTED","totalAmount":50000,
                 "failure":{"code":"REJECT_CARD_COMPANY","message":"카드사 거절"}}""");

        PaymentGatewayInquiryResult result = gateway.inquireByOrderId(ORDER_ID);

        assertThat(result.status()).isEqualTo(PaymentGatewayInquiryResult.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo("REJECT_CARD_COMPANY");
        assertThat(result.failureMessage()).isEqualTo("카드사 거절");
        assertThat(result.approvedAt()).isNull();
    }

    @Test
    void 토스에_결제가_없으면_예외가_아니라_NOT_FOUND_결과를_돌려준다() {
        server.reply(404, """
                {"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 정보 입니다."}""");

        PaymentGatewayInquiryResult result = gateway.inquireByOrderId(ORDER_ID);

        assertThat(result.status()).isEqualTo(PaymentGatewayInquiryResult.Status.NOT_FOUND);
    }

    @Test
    void 결제_없음_코드가_없는_404는_NOT_FOUND_로_읽지_않고_PgUnknownException_이다() {
        server.reply(404, "<html>not found</html>");

        assertThatThrownBy(() -> gateway.inquireByOrderId(ORDER_ID))
                .isInstanceOf(PgUnknownException.class);
    }

    @Test
    void 조회가_5xx_이면_PgUnknownException_이다() {
        server.reply(502, "{}");

        assertThatThrownBy(() -> gateway.inquireByPaymentKey(PAYMENT_KEY))
                .isInstanceOf(PgUnknownException.class);
    }

    @Test
    void 조회_중_연결이_거부되면_PgNotSentException_이다() {
        FakeTossServer closed = FakeTossServer.start();
        String deadUrl = closed.baseUrl();
        closed.close();
        TossPaymentGateway toDeadServer =
                new TossPaymentGateway(webClientTo(deadUrl), newCircuitBreaker(), Clock.system(SEOUL), SECRET);

        assertThatThrownBy(() -> toDeadServer.inquireByOrderId(ORDER_ID))
                .isInstanceOf(PgNotSentException.class);
    }

    @Test
    void 서킷이_열린_뒤의_조회도_PgNotSentException_이다() {
        server.reply(500, "{}");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gateway.inquireByOrderId(ORDER_ID)).isInstanceOf(PgUnknownException.class);
        }

        assertThatThrownBy(() -> gateway.inquireByPaymentKey(PAYMENT_KEY))
                .isInstanceOf(PgNotSentException.class);
    }

    // --- 비밀키 ----------------------------------------------------------------------------

    @Test
    void 비밀키는_어떤_실패에서도_예외_메시지와_로그에_남지_않는다() {
        String echoed = "{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"키 %s 가 거절됨\"}".formatted(SECRET);
        server.reply(400, echoed);
        Throwable rejected = catchThrowable(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT));
        server.reply(500, "{\"code\":\"X\",\"message\":\"" + SECRET + "\"}");
        Throwable unknown = catchThrowable(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT));
        server.replyAfter(1_500, 200, DONE_BODY);
        Throwable timedOut = catchThrowable(() -> gateway.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT));
        server.reply(500, "{}");
        Throwable inquiry = catchThrowable(() -> gateway.inquireByOrderId(ORDER_ID));

        String basic = Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8));
        for (Throwable thrown : new Throwable[] {rejected, unknown, timedOut, inquiry}) {
            for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
                assertThat(String.valueOf(cause.getMessage())).doesNotContain(SECRET).doesNotContain(basic);
            }
        }
        assertThat(rejected.getMessage()).contains("****");
        assertThat(logs.list).isNotEmpty().allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET).doesNotContain(basic));
    }

    private static Throwable catchThrowable(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("예외가 나야 하는 호출이었다");
    }
}
