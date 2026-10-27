package com.launchcatch.coupon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

class CouponPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CouponSchedule.class)
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            });

    @Test
    void 발급_시간을_바꾸면_오픈과_종료와_큐_준비가_함께_바뀐다() {
        runner.withPropertyValues("coupon.issue-start-time=09:30:15", "coupon.issue-end-time=16:45:30",
                "coupon.batch.queue-preparation-lead=10m").run(context -> {
            assertThat(context).hasNotFailed();
            CouponSchedule schedule = context.getBean(CouponSchedule.class);
            LocalDateTime midnight = LocalDateTime.of(2026, 10, 27, 0, 0);
            assertThat(CronExpression.parse(schedule.openCron()).next(midnight)).isEqualTo(midnight.withHour(9).withMinute(30).withSecond(15));
            assertThat(CronExpression.parse(schedule.closeCron()).next(midnight)).isEqualTo(midnight.withHour(16).withMinute(45).withSecond(30));
            assertThat(CronExpression.parse(schedule.queuePreparationCron()).next(midnight)).isEqualTo(midnight.withHour(9).withMinute(20).withSecond(15));
            assertThat(schedule.zone()).isEqualTo("Asia/Seoul");
        });
    }

    @Test
    void 일일_한도를_세_장보다_높게_설정할_수_있다() {
        runner.withPropertyValues("coupon.daily-limit=5").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CouponProperties.class).dailyLimit()).isEqualTo(5);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"coupon.daily-limit=0", "coupon.issue.batch.workers=0",
            "coupon.issue.batch.max-size=501", "coupon.issue.batch.wait-timeout=0s",
            "coupon.issue.batch.reservation-poll-interval=0s", "coupon.sequence.rebuild-batch-size=0",
            "coupon.batch.page-size=0", "coupon.batch.max-retries=-1", "coupon.sold-out.retry-interval=0s",
            "coupon.qr.image-size=0", "coupon.issue-start-time=15:00", "coupon.issue-start-time=00:01",
            "coupon.batch.queue-preparation-lead=0.5s"})
    void 유효하지_않은_설정은_시작할_때_거절한다(String invalidProperty) {
        runner.withPropertyValues(invalidProperty).run(context -> assertThat(context).hasFailed());
    }
}
