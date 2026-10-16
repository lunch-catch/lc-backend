package com.launchcatch.coupon.job;

import com.launchcatch.coupon.service.CouponFunnelBatchService;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponFunnelJob {

    private final CouponFunnelBatchService batches;
    private final Clock clock;

    @Scheduled(cron = "0 0 11 * * *", zone = "Asia/Seoul")
    public void open() {
        batches.open(LocalDate.now(clock.withZone(ClockConfig.ZONE)));
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void expire() {
        batches.expire(LocalDateTime.now(clock.withZone(ClockConfig.ZONE)));
    }
}
