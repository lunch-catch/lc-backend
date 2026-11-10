package com.lunchcatch.billing.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.payment.config.TossClientConfig;
import com.lunchcatch.billing.payment.config.TossProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LoadTestPaymentGatewayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    // 서버 여러 대가 함께 보는 Valkey 를 맵 하나로 흉내 낸다
    private final Map<String, String> sharedStore = new HashMap<>();

    @SuppressWarnings("unchecked")
    private StringRedisTemplate sharedRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> sharedStore.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(any())).thenAnswer(invocation -> sharedStore.get(invocation.<String>getArgument(0)));
        return redis;
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate failingRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisConnectionFailureException down = new RedisConnectionFailureException("valkey down");
        doThrow(down).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(any())).thenThrow(down);
        return redis;
    }

    private LoadTestPaymentGateway gateway() {
        return new LoadTestPaymentGateway(CLOCK, Duration.ZERO, "none", sharedRedis());
    }

    @Test
    void 승인은_받은_주문과_금액으로_성공한다() {
        PaymentGatewayApproval approval = gateway().confirm("loadtest_pk_1", "ord_1", 10_000L);

        assertThat(approval.paymentKey()).isEqualTo("loadtest_pk_1");
        assertThat(approval.orderId()).isEqualTo("ord_1");
        assertThat(approval.amount()).isEqualTo(10_000L);
        assertThat(approval.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 0));
    }

    @Test
    void 승인한_결제는_주문_번호와_결제_키로_조회하면_승인으로_답한다() {
        LoadTestPaymentGateway gateway = gateway();
        gateway.confirm("loadtest_pk_2", "ord_2", 30_000L);

        PaymentGatewayInquiryResult byOrder = gateway.inquireByOrderId("ord_2");
        PaymentGatewayInquiryResult byKey = gateway.inquireByPaymentKey("loadtest_pk_2");

        assertThat(byOrder.status()).isEqualTo(PaymentGatewayInquiryResult.Status.APPROVED);
        assertThat(byOrder.amount()).isEqualTo(30_000L);
        assertThat(byKey.status()).isEqualTo(PaymentGatewayInquiryResult.Status.APPROVED);
        assertThat(byKey.orderId()).isEqualTo("ord_2");
    }

    @Test
    void 다른_서버의_목이_승인한_결제도_승인으로_답한다() {
        LoadTestPaymentGateway appServer = gateway();
        LoadTestPaymentGateway batchServer = gateway();
        appServer.confirm("loadtest_pk_4", "ord_4", 20_000L);

        PaymentGatewayInquiryResult byKey = batchServer.inquireByPaymentKey("loadtest_pk_4");
        PaymentGatewayInquiryResult byOrder = batchServer.inquireByOrderId("ord_4");

        assertThat(byKey.status()).isEqualTo(PaymentGatewayInquiryResult.Status.APPROVED);
        assertThat(byKey.paymentKey()).isEqualTo("loadtest_pk_4");
        assertThat(byKey.orderId()).isEqualTo("ord_4");
        assertThat(byKey.amount()).isEqualTo(20_000L);
        assertThat(byKey.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 0));
        assertThat(byOrder.status()).isEqualTo(PaymentGatewayInquiryResult.Status.APPROVED);
        assertThat(byOrder.paymentKey()).isEqualTo("loadtest_pk_4");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 승인_기록은_만료를_두고_쓴다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        new LoadTestPaymentGateway(CLOCK, Duration.ZERO, "none", redis).confirm("loadtest_pk_5", "ord_5", 10_000L);

        verify(values).set(eq("loadtest:payment:key:loadtest_pk_5"), anyString(), eq(Duration.ofDays(2)));
        verify(values).set(eq("loadtest:payment:order:ord_5"), anyString(), eq(Duration.ofDays(2)));
    }

    @Test
    void 승인한_적_없는_결제는_없음으로_답한다() {
        LoadTestPaymentGateway gateway = gateway();

        assertThat(gateway.inquireByOrderId("ord_none").status())
                .isEqualTo(PaymentGatewayInquiryResult.Status.NOT_FOUND);
        assertThat(gateway.inquireByPaymentKey("pk_none").status())
                .isEqualTo(PaymentGatewayInquiryResult.Status.NOT_FOUND);
    }

    @Test
    void 승인_기록을_읽지_못하면_없음이_아니라_응답_불명으로_던진다() {
        LoadTestPaymentGateway gateway = new LoadTestPaymentGateway(CLOCK, Duration.ZERO, "none", failingRedis());

        assertThatThrownBy(() -> gateway.inquireByPaymentKey("loadtest_pk_6"))
                .isInstanceOf(PgUnknownException.class);
        assertThatThrownBy(() -> gateway.inquireByOrderId("ord_6"))
                .isInstanceOf(PgUnknownException.class);
    }

    @Test
    void 승인_기록을_쓰지_못해도_승인을_돌려준다() {
        LoadTestPaymentGateway gateway = new LoadTestPaymentGateway(CLOCK, Duration.ZERO, "none", failingRedis());

        PaymentGatewayApproval approval = gateway.confirm("loadtest_pk_7", "ord_7", 10_000L);

        assertThat(approval.paymentKey()).isEqualTo("loadtest_pk_7");
        assertThat(approval.amount()).isEqualTo(10_000L);
    }

    @Test
    void 정해진_시간만큼_기다린_뒤_승인한다() {
        LoadTestPaymentGateway gateway = new LoadTestPaymentGateway(CLOCK, Duration.ofMillis(50), "none", sharedRedis());

        long started = System.nanoTime();
        gateway.confirm("loadtest_pk_3", "ord_3", 10_000L);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void 토스_라이브_키가_있으면_만들지_않는다() {
        assertThatThrownBy(() -> new LoadTestPaymentGateway(CLOCK, Duration.ZERO, "live_gsk_xxx", sharedRedis()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loadtest_프로필이면_목만_있고_토스_연결은_만들지_않는다() {
        new ApplicationContextRunner()
                .withBean(Clock.class, () -> CLOCK)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withUserConfiguration(LoadTestPaymentGateway.class, TossPaymentGateway.class, TossClientConfig.class)
                .withPropertyValues("spring.profiles.active=prod,loadtest")
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentGateway.class);
                    assertThat(context).hasSingleBean(LoadTestPaymentGateway.class);
                    assertThat(context).doesNotHaveBean(TossPaymentGateway.class);
                    assertThat(context).doesNotHaveBean(TossProperties.class);
                });
    }

    @Test
    void loadtest_프로필이_아니면_목을_만들지_않는다() {
        new ApplicationContextRunner()
                .withBean(Clock.class, () -> CLOCK)
                .withUserConfiguration(LoadTestPaymentGateway.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(LoadTestPaymentGateway.class));
    }
}
