package com.launchcatch.billing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.billing.config.TossClientConfig;
import com.launchcatch.billing.config.TossProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoadTestPaymentGatewayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private LoadTestPaymentGateway gateway() {
        return new LoadTestPaymentGateway(CLOCK, Duration.ZERO, "none");
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
    void 승인한_적_없는_결제는_없음으로_답한다() {
        LoadTestPaymentGateway gateway = gateway();

        assertThat(gateway.inquireByOrderId("ord_none").status())
                .isEqualTo(PaymentGatewayInquiryResult.Status.NOT_FOUND);
        assertThat(gateway.inquireByPaymentKey("pk_none").status())
                .isEqualTo(PaymentGatewayInquiryResult.Status.NOT_FOUND);
    }

    @Test
    void 정해진_시간만큼_기다린_뒤_승인한다() {
        LoadTestPaymentGateway gateway = new LoadTestPaymentGateway(CLOCK, Duration.ofMillis(50), "none");

        long started = System.nanoTime();
        gateway.confirm("loadtest_pk_3", "ord_3", 10_000L);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void 토스_라이브_키가_있으면_만들지_않는다() {
        assertThatThrownBy(() -> new LoadTestPaymentGateway(CLOCK, Duration.ZERO, "live_gsk_xxx"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loadtest_프로필이면_목만_있고_토스_연결은_만들지_않는다() {
        new ApplicationContextRunner()
                .withBean(Clock.class, () -> CLOCK)
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
