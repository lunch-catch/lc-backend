package com.lunchcatch.coupon.config;

import com.lunchcatch.global.config.ClockConfig;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration("couponSchedule")
@EnableConfigurationProperties(CouponProperties.class)
@RequiredArgsConstructor
public class CouponSchedule {

    private final CouponProperties properties;

    public String openCron() {
        return dailyCron(properties.issueStartTime());
    }

    public String closeCron() {
        return dailyCron(properties.issueEndTime());
    }

    public String queuePreparationCron() {
        return dailyCron(properties.issueStartTime().minus(properties.batch().queuePreparationLead()));
    }

    public String zone() {
        return ClockConfig.ZONE.getId();
    }

    private String dailyCron(LocalTime time) {
        return "%d %d %d * * *".formatted(time.getSecond(), time.getMinute(), time.getHour());
    }
}
