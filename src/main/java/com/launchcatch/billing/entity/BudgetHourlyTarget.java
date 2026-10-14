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
 * 그날 계획의 시간대별 목표 소진액이다. 진행률 조회가 읽기만 한다.
 *
 * 행을 만드는 일은 00:00 예약 작업과 함께 더한다. 그래서 이 엔티티에는 생성 메서드가 없다.
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
}
