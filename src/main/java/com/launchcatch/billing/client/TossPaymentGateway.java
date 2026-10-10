package com.launchcatch.billing.client;

import com.launchcatch.billing.config.TossCircuitBreakerConfig;
import com.launchcatch.billing.config.TossProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/*
 * 토스페이먼츠를 부르는 PaymentGateway 구현.
 *
 * 서킷은 호출만 감싼다. 서킷 안에서는 WebClient 의 원래 예외를 그대로 던지고, PG 예외로 바꾸는 일은
 * 서킷 바깥의 catch 에서 한다. 그래야 서킷의 실패 판정(TossCircuitBreakerConfig)이 상태 코드를 볼 수 있고,
 * 서킷이 열려서 던지는 CallNotPermittedException 도 같은 자리에서 NOT_SENT 로 바꿀 수 있다.
 *
 * 분류의 원칙은 하나다. 확실한 것만 확실하다고 말하고, 모르면 UNKNOWN 이다.
 * UNKNOWN 은 받은 쪽이 조회로 확인하게 만들 뿐이라 비용이 작지만, 거절이나 미전송을 잘못 단정하면
 * 승인된 결제를 실패로 처리하거나 이중 승인을 시도한다.
 *
 * 이 클래스는 트랜잭션을 걸지 않는다. 부르는 쪽이 트랜잭션 밖에서 부른다.
 */
@Slf4j
@Component
public class TossPaymentGateway implements PaymentGateway {

    private static final String CONFIRM_URI = "/v1/payments/confirm";
    private static final String INQUIRE_BY_ORDER_URI = "/v1/payments/orders/{orderId}";
    private static final String INQUIRE_BY_PAYMENT_KEY_URI = "/v1/payments/{paymentKey}";

    private static final String STATUS_DONE = "DONE";
    private static final String NOT_FOUND_CODE = "NOT_FOUND_PAYMENT";

    /*
     * 확실한 거절만 담은 허용 목록이다. docs.tosspayments.com/reference/error-codes 기준이다.
     * 여기 없는 코드는 모두 UNKNOWN 으로 떨어진다. 토스가 새 코드를 추가해도 안전한 쪽(조회로 재확인)으로 동작한다.
     *
     * ALREADY_PROCESSED_PAYMENT 는 일부러 뺐다. 이전 시도가 승인까지 갔다는 뜻일 수 있어서,
     * 거절로 단정하면 이미 결제된 주문을 실패로 처리한다.
     */
    private static final Set<String> DEFINITE_REJECT_CODES = Set.of(
            "REJECT_CARD_COMPANY", "REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_PAYMENT",
            "REJECT_TOSSPAY_INVALID_ACCOUNT",
            "INVALID_REJECT_CARD", "INVALID_CARD_EXPIRATION", "INVALID_STOPPED_CARD",
            "INVALID_CARD_LOST_OR_STOLEN", "INVALID_CARD_NUMBER", "INVALID_PASSWORD",
            "INVALID_ACCOUNT_INFO_RE_REGISTER", "INVALID_UNREGISTERED_SUBMALL", "RESTRICTED_TRANSFER_ACCOUNT",
            "EXCEED_MAX_DAILY_PAYMENT_COUNT", "EXCEED_MAX_PAYMENT_AMOUNT", "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
            "EXCEED_MAX_AMOUNT", "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT", "EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT",
            "EXCEED_MAX_ONE_DAY_AMOUNT", "EXCEED_MAX_AUTH_COUNT",
            "NOT_ALLOWED_POINT_USE", "BELOW_MINIMUM_AMOUNT",
            "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT", "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
            "INVALID_CARD_INSTALLMENT_PLAN", "EXCEED_MAX_CARD_INSTALLMENT_PLAN",
            "NOT_AVAILABLE_PAYMENT", "NOT_AVAILABLE_BANK",
            "UNAPPROVED_ORDER_ID", "NOT_REGISTERED_BUSINESS", "FDS_ERROR",
            "NOT_FOUND_PAYMENT_SESSION", NOT_FOUND_CODE);

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Clock clock;
    private final TossSecretMasker masker;

    @Autowired
    public TossPaymentGateway(@Qualifier("tossPaymentWebClient") WebClient webClient,
            CircuitBreakerRegistry circuitBreakerRegistry, Clock clock, TossProperties properties) {
        this(webClient, circuitBreakerRegistry.circuitBreaker(TossCircuitBreakerConfig.INSTANCE), clock,
                properties.secretKey());
    }

    TossPaymentGateway(WebClient webClient, CircuitBreaker circuitBreaker, Clock clock, String secretKey) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.clock = clock;
        this.masker = new TossSecretMasker(secretKey);
    }

    @Override
    public PaymentGatewayApproval confirm(String paymentKey, String orderId, long amount) {
        TossPaymentResponse response;
        try {
            response = circuitBreaker.executeSupplier(() -> postConfirm(paymentKey, orderId, amount));
        } catch (CallNotPermittedException e) {
            log.warn("event=TOSS_CONFIRM_NOT_SENT orderId={} reason=CIRCUIT_OPEN", orderId);
            throw new PgNotSentException("토스 서킷이 열려 있어 승인 요청을 보내지 않았다", e);
        } catch (RuntimeException e) {
            throw classifyConfirmFailure(orderId, e);
        }
        return toApproval(orderId, response);
    }

    @Override
    public PaymentGatewayInquiryResult inquireByOrderId(String orderId) {
        return inquire(INQUIRE_BY_ORDER_URI, orderId);
    }

    @Override
    public PaymentGatewayInquiryResult inquireByPaymentKey(String paymentKey) {
        return inquire(INQUIRE_BY_PAYMENT_KEY_URI, paymentKey);
    }

    private TossPaymentResponse postConfirm(String paymentKey, String orderId, long amount) {
        return webClient.post()
                .uri(CONFIRM_URI)
                /*
                 * 같은 승인 요청이 중복 전송돼도 토스가 한 번만 처리하게 한다.
                 * 응답을 못 받은 승인을 다시 보낼 때 이중 승인이 나지 않는다.
                 * paymentKey 는 한 결제 시도 동안 바뀌지 않으므로 그대로 멱등 키로 쓴다.
                 */
                .header("Idempotency-Key", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TossConfirmRequest(paymentKey, orderId, amount))
                .retrieve()
                .bodyToMono(TossPaymentResponse.class)
                .block();
    }

    private TossPaymentResponse get(String uri, String id) {
        return webClient.get()
                .uri(uri, id)
                .retrieve()
                .bodyToMono(TossPaymentResponse.class)
                .block();
    }

    private PaymentGatewayInquiryResult inquire(String uri, String id) {
        TossPaymentResponse response;
        try {
            response = circuitBreaker.executeSupplier(() -> get(uri, id));
        } catch (CallNotPermittedException e) {
            log.warn("event=TOSS_INQUIRE_NOT_SENT reason=CIRCUIT_OPEN");
            throw new PgNotSentException("토스 서킷이 열려 있어 조회 요청을 보내지 않았다", e);
        } catch (RuntimeException e) {
            if (isPaymentNotFound(e)) {
                return PaymentGatewayInquiryResult.notFound();
            }
            throw classifyInquiryFailure(e);
        }
        return toInquiryResult(response);
    }

    // --- 실패 분류 ---------------------------------------------------------------------

    private RuntimeException classifyConfirmFailure(String orderId, RuntimeException e) {
        WebClientResponseException http = causeOfType(e, WebClientResponseException.class);
        if (http != null) {
            TossErrorResponse error = readErrorBody(http);
            String code = error == null ? null : error.code();
            String message = masker.mask(error == null ? null : error.message());
            if (code != null && DEFINITE_REJECT_CODES.contains(code)) {
                log.info("event=TOSS_CONFIRM_REJECTED orderId={} code={}", orderId, code);
                return new PgRejectedException(code, message);
            }
            log.warn("event=TOSS_CONFIRM_UNKNOWN orderId={} httpStatus={} code={}",
                    orderId, http.getStatusCode().value(), code);
            return new PgUnknownException("토스 승인 응답을 확정할 수 없다 (httpStatus="
                    + http.getStatusCode().value() + ", code=" + code + ")", e);
        }
        if (isNotSent(e)) {
            log.warn("event=TOSS_CONFIRM_NOT_SENT orderId={} causeType={}", orderId, rootCauseType(e));
            return new PgNotSentException("토스에 승인 요청이 나가지 않았다 (" + rootCauseType(e) + ")", e);
        }
        log.warn("event=TOSS_CONFIRM_UNKNOWN orderId={} causeType={}", orderId, rootCauseType(e));
        return new PgUnknownException("토스 승인 결과를 알 수 없다 (" + rootCauseType(e) + ")", e);
    }

    // 조회는 부작용이 없으므로 거절 개념이 없다. 나갔는지 여부만 가른다.
    private RuntimeException classifyInquiryFailure(RuntimeException e) {
        WebClientResponseException http = causeOfType(e, WebClientResponseException.class);
        if (http != null) {
            log.warn("event=TOSS_INQUIRE_UNKNOWN httpStatus={}", http.getStatusCode().value());
            return new PgUnknownException("토스 조회 응답을 처리할 수 없다 (httpStatus="
                    + http.getStatusCode().value() + ")", e);
        }
        if (isNotSent(e)) {
            log.warn("event=TOSS_INQUIRE_NOT_SENT causeType={}", rootCauseType(e));
            return new PgNotSentException("토스에 조회 요청이 나가지 않았다 (" + rootCauseType(e) + ")", e);
        }
        log.warn("event=TOSS_INQUIRE_UNKNOWN causeType={}", rootCauseType(e));
        return new PgUnknownException("토스 조회 결과를 알 수 없다 (" + rootCauseType(e) + ")", e);
    }

    /*
     * 요청이 나가지 않았음이 확실한 경우만 true 다.
     * 연결 거부와 연결 타임아웃(ConnectException), DNS 실패(UnknownHostException)다.
     * 연결 뒤에 끊기거나 TLS 중에 실패한 것은 요청이 나갔는지 알 수 없어 포함하지 않는다.
     */
    private boolean isNotSent(Throwable e) {
        return causeOfType(e, ConnectException.class) != null || causeOfType(e, UnknownHostException.class) != null;
    }

    // 404 라도 토스가 "결제 없음" 코드를 준 경우만 인정한다. 주소가 틀려 생긴 404 를 결제 없음으로 읽으면 안 된다.
    private boolean isPaymentNotFound(RuntimeException e) {
        WebClientResponseException http = causeOfType(e, WebClientResponseException.class);
        if (http == null || http.getStatusCode().value() != 404) {
            return false;
        }
        TossErrorResponse error = readErrorBody(http);
        return error != null && NOT_FOUND_CODE.equals(error.code());
    }

    private TossErrorResponse readErrorBody(WebClientResponseException e) {
        try {
            return e.getResponseBodyAs(TossErrorResponse.class);
        } catch (RuntimeException parseFailure) {
            log.warn("event=TOSS_ERROR_BODY_PARSE_FAILED httpStatus={}", e.getStatusCode().value());
            return null;
        }
    }

    private static <T extends Throwable> T causeOfType(Throwable throwable, Class<T> type) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    private static String rootCauseType(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    // --- 응답 변환 ---------------------------------------------------------------------

    private PaymentGatewayApproval toApproval(String orderId, TossPaymentResponse response) {
        /*
         * 승인 성공은 DONE 뿐이다. 가상계좌처럼 발급만 되고 입금을 기다리는 상태가 섞여 들어오면
         * 승인으로 단정하지 않고 UNKNOWN 으로 넘겨 조회로 실제 상태를 확인하게 한다.
         */
        if (response == null || !STATUS_DONE.equals(response.status())) {
            String actual = response == null ? "null" : response.status();
            log.warn("event=TOSS_CONFIRM_UNEXPECTED_STATUS orderId={} status={}", orderId, actual);
            throw new PgUnknownException("토스 승인 응답의 상태가 DONE 이 아니다 (status=" + actual + ")", null);
        }
        try {
            return new PaymentGatewayApproval(
                    Objects.requireNonNull(response.paymentKey()),
                    Objects.requireNonNull(response.orderId()),
                    Objects.requireNonNull(response.totalAmount()),
                    toLocalDateTime(response.approvedAt()));
        } catch (RuntimeException e) {
            log.warn("event=TOSS_CONFIRM_UNREADABLE_RESPONSE orderId={}", orderId);
            throw new PgUnknownException("토스 승인 응답을 해석할 수 없다", e);
        }
    }

    private PaymentGatewayInquiryResult toInquiryResult(TossPaymentResponse response) {
        if (response == null) {
            throw new PgUnknownException("토스 조회 응답이 비어 있다", null);
        }
        try {
            return new PaymentGatewayInquiryResult(
                    toInquiryStatus(response.status()),
                    response.paymentKey(),
                    response.orderId(),
                    Objects.requireNonNull(response.totalAmount()),
                    response.approvedAt() == null ? null : toLocalDateTime(response.approvedAt()),
                    response.failure() == null ? null : response.failure().code(),
                    response.failure() == null ? null : masker.mask(response.failure().message()));
        } catch (RuntimeException e) {
            log.warn("event=TOSS_INQUIRE_UNREADABLE_RESPONSE");
            throw new PgUnknownException("토스 조회 응답을 해석할 수 없다", e);
        }
    }

    // 모르는 상태는 PENDING 이다. 여기서 승인이나 실패로 단정하지 않는다.
    private static PaymentGatewayInquiryResult.Status toInquiryStatus(String tossStatus) {
        if (tossStatus == null) {
            return PaymentGatewayInquiryResult.Status.PENDING;
        }
        return switch (tossStatus) {
            case STATUS_DONE -> PaymentGatewayInquiryResult.Status.APPROVED;
            case "CANCELED", "PARTIAL_CANCELED" -> PaymentGatewayInquiryResult.Status.CANCELED;
            case "ABORTED", "EXPIRED" -> PaymentGatewayInquiryResult.Status.FAILED;
            default -> PaymentGatewayInquiryResult.Status.PENDING;
        };
    }

    private LocalDateTime toLocalDateTime(String isoOffsetDateTime) {
        return OffsetDateTime.parse(isoOffsetDateTime).atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
