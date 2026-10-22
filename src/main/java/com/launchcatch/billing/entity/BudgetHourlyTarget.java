package com.launchcatch.billing.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 그날 계획의 시간대별 목표 소진액이다. 00:00 의 시간대별 목표 단계가 만들고 진행률 조회가 읽는다.
 *
 * hour 컬럼이 TINYINT 라서 byte 로 매핑한다. int 로 두면 기동 때 스키마 검증이 타입 불일치로 막는다.
 */
@Entity
@Table(name = "budget_hourly_target")
@AttributeOverride(name = "id", column = @Column(name = "budget_hourly_target_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetHourlyTarget extends BaseTimeEntity {

    @Column(name = "budget_daily_plan_id", nullable = false, updatable = false)
    private Long budgetDailyPlanId;

    @Column(name = "hour", nullable = false, updatable = false)
    private byte hour;

    @Column(name = "target_amount", nullable = false, updatable = false)
    private long targetAmount;

    private BudgetHourlyTarget(Long budgetDailyPlanId, int hour, long targetAmount) {
        if (budgetDailyPlanId == null) {
            throw new IllegalArgumentException("계획 ID는 필수입니다.");
        }
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("시는 0 이상 23 이하여야 합니다: " + hour);
        }
        if (targetAmount < 0) {
            throw new IllegalArgumentException("목표 금액은 0 이상이어야 합니다: " + targetAmount);
        }
        this.budgetDailyPlanId = budgetDailyPlanId;
        this.hour = (byte) hour;
        this.targetAmount = targetAmount;
    }

    // 계획 하나의 한 시간대 목표다. 같은 계획과 시는 한 행뿐이다(uk_budget_hourly_target_plan_hour)
    public static BudgetHourlyTarget create(Long budgetDailyPlanId, int hour, long targetAmount) {
        return new BudgetHourlyTarget(budgetDailyPlanId, hour, targetAmount);
    }
}
