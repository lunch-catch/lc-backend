package com.launchcatch.campaign.entity;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "campaign")
@AttributeOverride(name = "id", column = @Column(name = "campaign_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign extends BaseTimeEntity {

    private static final int NOTICE_MAX_LENGTH = 500;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignStatus status;

    @Column(name = "status_changed_at", nullable = false)
    private LocalDateTime statusChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "paused_reason", length = 20)
    private PauseReason pausedReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_target_type", nullable = false, length = 20)
    private DiscountTargetType discountTargetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private int discountValue;

    @Column(name = "target_menu_id")
    private Long targetMenuId;

    @Column(name = "issue_quantity", nullable = false)
    private int issueQuantity;

    @Column(name = "usable_start_time", nullable = false)
    private LocalTime usableStartTime;

    @Column(name = "usable_end_time", nullable = false)
    private LocalTime usableEndTime;

    @Column(name = "min_order_amount")
    private Integer minOrderAmount;

    @Column(length = NOTICE_MAX_LENGTH)
    private String notice;

    @Column(name = "target_radius", nullable = false)
    private int targetRadius;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_gender", nullable = false, length = 10)
    private TargetGender targetGender;

    @Convert(converter = AgeGroupListConverter.class)
    @Column(name = "target_age_groups", nullable = false, length = 50)
    private List<AgeGroup> targetAgeGroups;

    /*
     * V3__campaign_draft_nullable.sql 이 단계별 저장 중인 DRAFT가 예산을 아직
     * 정하지 않을 수 있게 이 컬럼을 NULL 허용으로 바꿔 뒀다. primitive long이면
     * NULL 행을 읽는 순간 Hibernate가 매핑에 실패한다.
     */
    @Column(name = "daily_budget")
    private Long dailyBudget;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    private Campaign(
            Long ownerId, Long storeId,
            DiscountTargetType discountTargetType, DiscountType discountType, int discountValue, Long targetMenuId,
            int issueQuantity, LocalTime usableStartTime, LocalTime usableEndTime,
            Integer minOrderAmount, String notice,
            int targetRadius, TargetGender targetGender, List<AgeGroup> targetAgeGroups,
            Long dailyBudget, LocalDate startDate, LocalDate endDate,
            LocalDateTime now) {
        requireDiscountTarget(discountTargetType, targetMenuId);
        requireDiscountValue(discountType, discountValue);
        requirePeriod(startDate, endDate);
        requireUsableTime(usableStartTime, usableEndTime);
        requireQuantity(issueQuantity);
        requireDailyBudget(dailyBudget);
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.status = CampaignStatus.DRAFT;
        this.statusChangedAt = now;
        this.discountTargetType = discountTargetType;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.targetMenuId = targetMenuId;
        this.issueQuantity = issueQuantity;
        this.usableStartTime = usableStartTime;
        this.usableEndTime = usableEndTime;
        this.minOrderAmount = minOrderAmount;
        this.notice = notice;
        this.targetRadius = targetRadius;
        this.targetGender = Objects.requireNonNull(targetGender, "targetGender");
        this.targetAgeGroups = requireNonEmpty(targetAgeGroups);
        this.dailyBudget = dailyBudget;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public static Campaign create(
            Long ownerId, Long storeId,
            DiscountTargetType discountTargetType, DiscountType discountType, int discountValue, Long targetMenuId,
            int issueQuantity, LocalTime usableStartTime, LocalTime usableEndTime,
            Integer minOrderAmount, String notice,
            int targetRadius, TargetGender targetGender, List<AgeGroup> targetAgeGroups,
            Long dailyBudget, LocalDate startDate, LocalDate endDate,
            LocalDateTime now) {
        return new Campaign(
                ownerId, storeId,
                discountTargetType, discountType, discountValue, targetMenuId,
                issueQuantity, usableStartTime, usableEndTime,
                minOrderAmount, notice,
                targetRadius, targetGender, targetAgeGroups,
                dailyBudget, startDate, endDate,
                now);
    }

    public void schedule(LocalDateTime now) {
        if (this.status != CampaignStatus.DRAFT) {
            throw new IllegalStateException("only DRAFT campaign can be scheduled: " + this.status);
        }
        this.status = CampaignStatus.SCHEDULED;
        this.statusChangedAt = now;
    }

    public void activate(LocalDateTime now) {
        if (this.status != CampaignStatus.SCHEDULED) {
            throw new IllegalStateException("only SCHEDULED campaign can be activated: " + this.status);
        }
        this.status = CampaignStatus.ACTIVE;
        this.statusChangedAt = now;
    }

    public void pause(PauseReason reason, LocalDateTime now) {
        if (this.status != CampaignStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE campaign can be paused: " + this.status);
        }
        this.status = CampaignStatus.PAUSED;
        this.pausedReason = Objects.requireNonNull(reason, "reason");
        this.statusChangedAt = now;
    }

    public void resume(LocalDateTime now) {
        if (this.status != CampaignStatus.PAUSED) {
            throw new IllegalStateException("only PAUSED campaign can be resumed: " + this.status);
        }
        this.status = CampaignStatus.ACTIVE;
        this.pausedReason = null;
        this.statusChangedAt = now;
    }

    public void end(LocalDateTime now) {
        if (this.status != CampaignStatus.ACTIVE && this.status != CampaignStatus.PAUSED) {
            throw new IllegalStateException("only ACTIVE or PAUSED campaign can end: " + this.status);
        }
        this.status = CampaignStatus.ENDED;
        this.statusChangedAt = now;
    }

    private static void requireDiscountTarget(DiscountTargetType discountTargetType, Long targetMenuId) {
        boolean all = discountTargetType == DiscountTargetType.ALL && targetMenuId == null;
        boolean menu = discountTargetType == DiscountTargetType.MENU && targetMenuId != null;
        if (!all && !menu) {
            throw new IllegalArgumentException(
                    "targetMenuId must be null for ALL and required for MENU: " + discountTargetType);
        }
    }

    private static void requireDiscountValue(DiscountType discountType, int discountValue) {
        boolean positive = discountValue > 0;
        boolean withinPercent = discountType != DiscountType.PERCENT || discountValue <= 100;
        if (!positive || !withinPercent) {
            throw new IllegalArgumentException("discountValue is invalid for " + discountType + ": " + discountValue);
        }
    }

    /*
     * startDate, endDate는 V3__campaign_draft_nullable.sql 이후로 단계별 저장 중인
     * DRAFT가 아직 집행 기간을 안 정했을 수 있어 둘 다 NULL일 수 있다. 둘 다 있을
     * 때만 순서를 검사한다.
     */
    private static void requirePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
    }

    private static void requireUsableTime(LocalTime usableStartTime, LocalTime usableEndTime) {
        if (!usableEndTime.isAfter(usableStartTime)) {
            throw new IllegalArgumentException("usableEndTime must be after usableStartTime");
        }
    }

    private static void requireQuantity(int issueQuantity) {
        if (issueQuantity <= 0) {
            throw new IllegalArgumentException("issueQuantity must be positive: " + issueQuantity);
        }
    }

    /*
     * dailyBudget도 V3__campaign_draft_nullable.sql 이후로 단계별 저장 중인 DRAFT가
     * 아직 예산을 안 정했을 수 있어 NULL일 수 있다. 값이 있을 때만 양수인지 본다.
     */
    private static void requireDailyBudget(Long dailyBudget) {
        if (dailyBudget != null && dailyBudget <= 0) {
            throw new IllegalArgumentException("dailyBudget must be positive when present: " + dailyBudget);
        }
    }

    private static List<AgeGroup> requireNonEmpty(List<AgeGroup> targetAgeGroups) {
        if (targetAgeGroups == null || targetAgeGroups.isEmpty()) {
            throw new IllegalArgumentException("targetAgeGroups must not be empty");
        }
        return List.copyOf(targetAgeGroups);
    }
}
