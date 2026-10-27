package com.launchcatch.billing.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 캠페인 하나의 영업일 하루치 예산 계획이다. 00:00 예약이 캠페인과 영업일마다 한 행을 만든다.
 *
 * 차감의 정본이다. 그날 예약액(daily_budget), 노출 단가, 원장에 적을 점주 ID, 중단 여부를 갖는다.
 * 차감 핫패스는 이 행을 Valkey 해시로 미러링해 읽고, 해시가 없으면 이 행으로 다시 만든다.
 *
 * paused 와 status_changed_at 은 장중에 캠페인 상태 변경 수신이 바꾼다. 행은 00:00 예약이 create 로 만든다.
 */
@Entity
@Table(name = "budget_daily_plan")
@AttributeOverride(name = "id", column = @Column(name = "budget_daily_plan_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetDailyPlan extends BaseTimeEntity {

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    /** 00:00 예약 때 캠페인 조회로 받아 둔다. 노출 차감이 원장에 적는 점주다. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    /** 그날 예약액이다. 충전 직후 재예약이 늘릴 수 있다. */
    @Column(name = "daily_budget", nullable = false)
    private long dailyBudget;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    /** 그날 중단 여부다. 중단 뒤 도착한 노출은 과금하지 않는다(NOT_BILLED_PAUSED). */
    @Column(nullable = false)
    private boolean paused;

    /** 반영한 상태 변경 이벤트의 changedAt 이다. 이보다 늦은 이벤트만 반영한다. */
    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    // 그 계획의 예약을 해제한 시각이다. 차감이 마감을 판정한다(#1049). 해제가 저장소의 일괄 갱신으로 적는다
    @Column(name = "released_at", insertable = false, updatable = false)
    private LocalDateTime releasedAt;

    private BudgetDailyPlan(Long campaignId, Long ownerId, LocalDate businessDate, long dailyBudget,
                            long unitPrice, boolean paused, LocalDateTime statusChangedAt) {
        if (campaignId == null || ownerId == null || businessDate == null) {
            throw new IllegalArgumentException("캠페인 ID, 점주 ID, 영업일은 필수입니다.");
        }
        if (dailyBudget < 0) {
            throw new IllegalArgumentException("그날 예약액은 0 이상이어야 합니다: " + dailyBudget);
        }
        if (unitPrice <= 0) {
            throw new IllegalArgumentException("노출 단가는 0보다 커야 합니다: " + unitPrice);
        }
        this.campaignId = campaignId;
        this.ownerId = ownerId;
        this.businessDate = businessDate;
        this.dailyBudget = dailyBudget;
        this.unitPrice = unitPrice;
        this.paused = paused;
        this.statusChangedAt = statusChangedAt;
    }

    /*
     * 00:00 예약이 만드는 그날 계획이다. 예약액이 0 이어도 만든다(V52 102행 (5)).
     * 처음 중단 여부와 상태 변경 시각은 예약 때 읽은 캠페인 상태를 옮긴다. 그 시각보다 늦은 상태 변경만 반영되게 하기 위해서다.
     */
    public static BudgetDailyPlan create(Long campaignId, Long ownerId, LocalDate businessDate, long dailyBudget,
                                         long unitPrice, boolean paused, LocalDateTime statusChangedAt) {
        return new BudgetDailyPlan(campaignId, ownerId, businessDate, dailyBudget, unitPrice, paused, statusChangedAt);
    }

    /*
     * 충전 직후 재예약이 그날 예약액을 늘린다(I-03). 줄이는 경로는 두지 않는다. 호출하는 쪽이 늘어난
     * 몫만큼만 넘기므로 새 값이 지금 값보다 크지 않으면 호출 쪽 계산이 어긋난 것이라 예외로 멈춘다.
     */
    public void increaseDailyBudget(long newDailyBudget) {
        if (newDailyBudget <= dailyBudget) {
            throw new IllegalArgumentException(
                    "그날 예약액은 늘어날 때만 반영합니다: 기존=" + dailyBudget + " 새 값=" + newDailyBudget);
        }
        this.dailyBudget = newDailyBudget;
    }
}
