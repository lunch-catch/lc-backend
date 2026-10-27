package com.lunchcatch.billing.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 대사 배치가 찾은 불일치 한 건이다.
 *
 * 같은 (check_type, target_id, business_date) 는 한 행이다(uk_settlement_mismatch_check_target_date).
 * 행은 대사 배치가 저장소의 네이티브 upsert 로 만든다. 같은 날의 재실행이 예외 없이 흡수되어야 해서 엔티티 생성 경로를 두지 않는다.
 * 관리자 조회와, 관리자가 MANUAL_ADJUSTMENT 나 NO_ADJUSTMENT_REQUIRED 로 닫는 조건부 갱신도 이 테이블을 쓴다.
 */
@Entity
@Table(name = "settlement_mismatch")
@AttributeOverride(name = "id", column = @Column(name = "settlement_mismatch_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementMismatch extends BaseTimeEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 30, updatable = false)
    private MismatchCheckType checkType;

    /** 대사 대상 영업일이다(Asia/Seoul). */
    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    /** 결제 ID 나 캠페인 ID 다. 어느 쪽인지는 checkType 이 정한다. */
    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    /** 더 믿는 쪽의 값이다. 캠페인 대사에서는 원장이다. */
    @Column(name = "expected_value", nullable = false)
    private long expectedValue;

    /** 견준 쪽의 값이다. 캠페인 대사에서는 카운터다. */
    @Column(name = "actual_value", nullable = false)
    private long actualValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", length = 30)
    private MismatchResolutionType resolutionType;

    /** 운영의 batch_execution_log 를 가리키는 소프트 참조다. */
    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 닫은 관리자 ID 다. 배치가 자동으로 닫은 건은 NULL 이다. */
    @Column(name = "resolved_by")
    private Long resolvedBy;
}
