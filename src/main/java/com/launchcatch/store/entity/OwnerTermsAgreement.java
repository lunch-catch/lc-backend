package com.launchcatch.store.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "owner_terms_agreement", uniqueConstraints = @UniqueConstraint(
        name = "uk_owner_terms_agreement_owner", columnNames = "owner_id"))
@AttributeOverride(name = "id", column = @Column(name = "owner_terms_agreement_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerTermsAgreement extends BaseTimeEntity {
    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;
    @Column(nullable = false)
    private boolean required;
    @Column(nullable = false)
    private boolean agreed;
    @Column(name = "agreed_at")
    private LocalDateTime agreedAt;
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    private OwnerTermsAgreement(Long ownerId, LocalDateTime agreedAt) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("유효한 점주 ID가 필요합니다.");
        }
        this.ownerId = ownerId;
        if (agreedAt == null) {
            throw new IllegalArgumentException("동의 시각이 필요합니다.");
        }
        this.required = true;
        this.agreed = true;
        this.agreedAt = agreedAt;
        this.withdrawnAt = null;
    }

    public static OwnerTermsAgreement record(Long ownerId, LocalDateTime agreedAt) {
        return new OwnerTermsAgreement(ownerId, agreedAt);
    }
}