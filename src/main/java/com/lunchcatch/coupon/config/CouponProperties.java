package com.lunchcatch.coupon.config;

import com.lunchcatch.adserving.contract.WishlistQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.LocalTime;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("coupon")
public record CouponProperties(
        @Positive int dailyLimit,
        @NotNull LocalTime issueStartTime,
        @NotNull LocalTime issueEndTime,
        @Valid @NotNull Issue issue,
        @Valid @NotNull Sequence sequence,
        @Valid @NotNull Batch batch,
        @Valid @NotNull SoldOut soldOut,
        @Valid @NotNull Qr qr) {

    public CouponProperties {
        if (issueStartTime != null && issueEndTime != null
                && (!issueStartTime.isBefore(issueEndTime)
                || issueStartTime.getNano() != 0 || issueEndTime.getNano() != 0)) {
            throw new IllegalArgumentException("쿠폰 발급 시작과 종료는 초 단위이며 시작이 종료보다 빨라야 한다.");
        }
        if (issueStartTime != null && batch != null && batch.queuePreparationLead() != null
                && batch.queuePreparationLead().compareTo(Duration.ofSeconds(issueStartTime.toSecondOfDay())) > 0) {
            throw new IllegalArgumentException("쿠폰 큐 준비 시각은 영업일 안에 있어야 한다.");
        }
    }

    public record Issue(@Valid @NotNull IssuanceBatch batch,
                        @Valid @NotNull Recovery recovery,
                        @Valid @NotNull Shutdown shutdown) { }

    public record IssuanceBatch(
            @Positive int workers,
            @Positive @Max(WishlistQueryService.MAX_WISHED_MEMBER_IDS) int maxSize,
            @Positive int queueCapacity,
            @NotNull @DurationMin(millis = 1) Duration waitTimeout,
            @NotNull @DurationMin Duration collectionWindow,
            @NotNull @DurationMin(millis = 1) Duration idlePollInterval,
            @NotNull @DurationMin(millis = 1) Duration reservationPollInterval) { }

    public record Recovery(@Positive int workers) { }

    public record Shutdown(
            @NotNull @DurationMin Duration grace,
            @NotNull @DurationMin(millis = 1) Duration cleanupTimeout) { }

    public record Sequence(
            @NotNull @DurationMin(millis = 1) Duration staleAfter,
            @NotNull @DurationMin Duration retentionGrace,
            @NotNull @DurationMin(millis = 1) Duration retryDelay,
            @Positive int rebuildBatchSize) { }

    public record Batch(
            @Positive int pageSize,
            @PositiveOrZero int maxRetries,
            @NotBlank String preparationCron,
            @NotBlank String consistencyCron,
            @NotBlank String expirationCron,
            @NotNull @DurationMin(seconds = 1) Duration queuePreparationLead) {
        public Batch {
            if (queuePreparationLead != null && queuePreparationLead.getNano() != 0) {
                throw new IllegalArgumentException("쿠폰 큐 준비 여유시간은 초 단위여야 한다.");
            }
        }
    }

    public record SoldOut(
            @Positive int maxPending,
            @NotNull @DurationMin(millis = 1) Duration retryInterval,
            @NotNull @DurationMin(millis = 1) Duration retryWindow,
            @Positive long completedCacheSize,
            @NotNull @DurationMin(millis = 1) Duration completedCacheTtl,
            @NotNull @DurationMin(millis = 1) Duration pollInterval,
            @NotNull @DurationMin(millis = 1) Duration shutdownTimeout) { }

    public record Qr(@Positive int imageSize) { }
}
