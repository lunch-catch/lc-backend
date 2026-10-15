package com.launchcatch.billing.entity;

import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/*
 * 노출 하나의 과금 판정 기록이다. serve_id 마다 한 행이다.
 *
 * 쓰기는 DeductionResultRepository.insertIfAbsent 의 네이티브 INSERT 만 한다. 같은 serve_id 의 재호출을
 * 예외 없이 흡수해야 해서 JPA save 를 쓰지 않는다. 그래서 이 엔티티에는 생성 메서드가 없고 읽기 매핑만 둔다.
 */
@Entity
@Table(name = "deduction_result")
@AttributeOverride(name = "id", column = @Column(name = "deduction_result_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeductionResult extends BaseTimeEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "serve_id", nullable = false, updatable = false)
    private UUID serveId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_status", nullable = false, length = 20, updatable = false)
    private DeductionStatus billingStatus;

    /** 그날 단가다. BILLED 면 원장 DEDUCT 의 금액이다. */
    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;
}
