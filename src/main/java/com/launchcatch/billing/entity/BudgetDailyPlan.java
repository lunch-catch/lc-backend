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
 * paused 와 status_changed_at 은 장중에 캠페인 상태 변경 수신이 바꾼다. 이 판은 그 갱신에 필요한
 * 매핑만 둔다. 행을 만드는 팩터리는 00:00 예약 작업과 함께 더한다.
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
}
