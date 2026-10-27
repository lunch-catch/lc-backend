package com.lunchcatch.billing.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "pg_charge_reconciliation_pending")
@AttributeOverride(name = "id", column = @Column(name = "pg_charge_reconciliation_pending_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgChargeReconciliationPending extends BaseTimeEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "last_error", nullable = false, length = 500)
    private String lastError;

    private PgChargeReconciliationPending(Long paymentId, LocalDate businessDate, String lastError) {
        this.paymentId = paymentId;
        this.businessDate = businessDate;
        this.lastError = lastError;
    }

    public static PgChargeReconciliationPending pending(Long paymentId, LocalDate businessDate, String lastError) {
        return new PgChargeReconciliationPending(paymentId, businessDate, lastError);
    }
}
