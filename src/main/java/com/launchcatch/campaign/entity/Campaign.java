package com.launchcatch.campaign.entity;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
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

    /*
     * startDate, endDate도 V3__campaign_draft_nullable.sql 이 단계별 저장 중인 DRAFT가 아직
     * 집행 기간을 정하지 않을 수 있게 NULL 허용으로 바꿔 뒀다. dailyBudget과 같은 이유다.
     */
    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /*
     * ACTIVE에서 바꾼 하루 예산은 그날 서빙에 바로 반영하지 않고 다음 영업일 적용
     * 예약 값으로만 들고 있는다(V51 45행). 00:00 배치가 이 값을 dailyBudget에
     * 반영하는 쪽은 그 배치 이슈 몫이라(#330), 이 엔티티는 쓰기만 한다.
     */
    @Column(name = "pending_daily_budget")
    private Long pendingDailyBudget;

    private Campaign(
            Long ownerId, Long storeId,
            DiscountTargetType discountTargetType, DiscountType discountType, int discountValue, Long targetMenuId,
            int issueQuantity, LocalTime usableStartTime, LocalTime usableEndTime,
            Integer minOrderAmount, String notice,
            int targetRadius, TargetGender targetGender, List<AgeGroup> targetAgeGroups,
            Long dailyBudget, LocalDate startDate, LocalDate endDate,
            LocalDateTime now) {
        requireDiscountTarget(discountTargetType, targetMenuId);
        requireDiscountValue(discountTargetType, discountType, discountValue);
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

    /*
     * 캠페인 등록 1단계(쿠폰 조건)만 다시 받는다. 노출 대상(3단계)·예산과 기간(4단계)
     * 필드는 손대지 않는다(#244). DRAFT가 아닌 캠페인은 이 메서드를 부를 자리가 없어야
     * 하지만, 호출하는 쪽의 조회 조건이 바뀌어도 엔티티가 스스로 전제를 지키게 한다.
     */
    public void updateCouponCondition(
            DiscountTargetType discountTargetType, DiscountType discountType, int discountValue, Long targetMenuId,
            int issueQuantity, LocalTime usableStartTime, LocalTime usableEndTime,
            Integer minOrderAmount, String notice) {
        if (this.status != CampaignStatus.DRAFT) {
            throw new IllegalStateException("only DRAFT campaign can update coupon condition: " + this.status);
        }
        requireDiscountTarget(discountTargetType, targetMenuId);
        requireDiscountValue(discountTargetType, discountType, discountValue);
        requireUsableTime(usableStartTime, usableEndTime);
        requireQuantity(issueQuantity);
        this.discountTargetType = discountTargetType;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.targetMenuId = targetMenuId;
        this.issueQuantity = issueQuantity;
        this.usableStartTime = usableStartTime;
        this.usableEndTime = usableEndTime;
        this.minOrderAmount = minOrderAmount;
        this.notice = notice;
    }

    /*
     * 캠페인 등록 3단계(노출 대상)만 다시 받는다. 쿠폰 조건·예산·기간 필드는 손대지
     * 않는다(#253). updateCouponCondition과 같은 이유로 DRAFT 전제를 엔티티가 스스로
     * 지킨다.
     */
    public void updateTargetAudience(int targetRadius, TargetGender targetGender, List<AgeGroup> targetAgeGroups) {
        if (this.status != CampaignStatus.DRAFT) {
            throw new IllegalStateException("only DRAFT campaign can update target audience: " + this.status);
        }
        this.targetRadius = targetRadius;
        this.targetGender = Objects.requireNonNull(targetGender, "targetGender");
        this.targetAgeGroups = requireNonEmpty(targetAgeGroups);
    }

    /*
     * 캠페인 등록 4단계(하루 예산과 집행 기간)를 DRAFT에서 바로 반영한다. 쿠폰 조건·노출
     * 대상 필드는 손대지 않는다(#330). updateCouponCondition/updateTargetAudience와 같은
     * 이유로 DRAFT 전제를 엔티티가 스스로 지킨다.
     */
    public void updateBudgetAndPeriod(Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        if (this.status != CampaignStatus.DRAFT) {
            throw new IllegalStateException("only DRAFT campaign can update budget and period: " + this.status);
        }
        requireDailyBudget(dailyBudget);
        requirePeriod(startDate, endDate);
        this.dailyBudget = dailyBudget;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /*
     * ACTIVE에서 하루 예산을 바꾸면 다음 영업일 적용 예약 값으로만 저장한다. dailyBudget
     * 자체는 그대로 둬 그날 서빙에 영향을 주지 않는다(V51 45행, #330).
     */
    public void reserveDailyBudget(Long dailyBudget) {
        if (this.status != CampaignStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE campaign can reserve daily budget: " + this.status);
        }
        requireDailyBudget(dailyBudget);
        this.pendingDailyBudget = dailyBudget;
    }

    /*
     * 00:00 배치가 예약해 둔 하루 예산을 dailyBudget 에 옮긴다(#840). ACTIVE 이고 예약 값이 있을 때만 적용하고, 적용했으면
     * true 를 돌려준다. pause 는 예약 값을 지우지 않으므로 PAUSED 의 예약 값은 그대로 두어야 해서 상태를 다시 확인한다.
     */
    public boolean applyPendingDailyBudget() {
        if (this.status != CampaignStatus.ACTIVE || this.pendingDailyBudget == null) {
            return false;
        }
        this.dailyBudget = this.pendingDailyBudget;
        this.pendingDailyBudget = null;
        return true;
    }

    public void schedule(LocalDateTime now) {
        if (this.status != CampaignStatus.DRAFT) {
            throw new IllegalStateException("only DRAFT campaign can be scheduled: " + this.status);
        }
        this.status = CampaignStatus.SCHEDULED;
        this.statusChangedAt = now;
    }

    // 점주가 집행 전에 취소해 DRAFT 로 되돌린다(#571, #597). 집행이 시작된 뒤에는 되돌리지 않는다
    public void cancel(LocalDateTime now) {
        if (this.status != CampaignStatus.SCHEDULED) {
            throw new IllegalStateException("only SCHEDULED campaign can be cancelled: " + this.status);
        }
        this.status = CampaignStatus.DRAFT;
        this.statusChangedAt = now;
    }

    public void activate(LocalDateTime now) {
        if (this.status != CampaignStatus.SCHEDULED) {
            throw new IllegalStateException("only SCHEDULED campaign can be activated: " + this.status);
        }
        if (this.startDate == null || this.endDate == null) {
            throw new IllegalStateException("startDate and endDate are required to activate");
        }
        this.status = CampaignStatus.ACTIVE;
        this.statusChangedAt = now;
    }

    /*
     * pendingDailyBudget은 그대로 둔다(#330 자체 리뷰로 찾음). NO_POINTS로 중단된 뒤 같은 날
     * 충전으로 다시 재개되는 경우(V51 51·49행)가 있어, 중단을 "끝"이 아니라 "잠깐 멈춤"으로
     * 본다. 재개 뒤에도 그 예약값이 남아 있어야 점주가 걸어 둔 변경 의도가 사라지지 않는다.
     */
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

    /*
     * pendingDailyBudget을 지운다(#330 자체 리뷰로 찾음). ENDED는 되돌릴 수 없는 종료
     * 상태라(resume은 PAUSED에서만 가능) 이 값을 다시 쓸 일이 없다. 지우지 않으면
     * 죽은 예약값이 테이블에 그대로 남는다.
     */
    public void end(LocalDateTime now) {
        if (this.status != CampaignStatus.ACTIVE && this.status != CampaignStatus.PAUSED) {
            throw new IllegalStateException("only ACTIVE or PAUSED campaign can end: " + this.status);
        }
        this.status = CampaignStatus.ENDED;
        this.pausedReason = null;
        this.pendingDailyBudget = null;
        this.statusChangedAt = now;
    }

    /*
     * 집행이 시작되기 전에 기간이 지난 SCHEDULED 를 끝낸다(#902). 한 번도 ACTIVE 가 되지 않았으므로 end() 가 지우는 중단
     * 사유와 예약 예산은 애초에 없다.
     */
    public void endBeforeStart(LocalDateTime now) {
        if (this.status != CampaignStatus.SCHEDULED) {
            throw new IllegalStateException("only SCHEDULED campaign can end before start: " + this.status);
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

    /*
     * discountTargetType이 ALL(전체 메뉴)이면 discountType은 AMOUNT(정액)만 허용한다(#431,
     * 팀 논의로 바뀐 사양). MENU(특정 메뉴)는 PERCENT/AMOUNT 둘 다 그대로 허용한다.
     */
    private static void requireDiscountValue(
            DiscountTargetType discountTargetType, DiscountType discountType, int discountValue) {
        boolean positive = discountValue > 0;
        boolean withinPercent = discountType != DiscountType.PERCENT || discountValue <= 100;
        boolean allowedForAll = discountTargetType != DiscountTargetType.ALL || discountType == DiscountType.AMOUNT;
        if (!positive || !withinPercent || !allowedForAll) {
            throw new IllegalArgumentException("discountValue is invalid for " + discountTargetType + "/"
                    + discountType + ": " + discountValue);
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
