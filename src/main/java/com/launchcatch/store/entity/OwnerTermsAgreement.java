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
        name = "uk_owner_terms_version", columnNames = {"owner_id", "terms_type", "terms_version"}))
@AttributeOverride(name = "id", column = @Column(name = "owner_terms_agreement_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerTermsAgreement extends BaseTimeEntity {
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(name = "terms_type", nullable = false, length = 50)
    private String termsType;
    @Column(name = "terms_version", nullable = false, length = 30)
    private String termsVersion;
    @Column(nullable = false)
    private boolean required;
    @Column(nullable = false)
    private boolean agreed;
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    private OwnerTermsAgreement(Long ownerId, String termsType, String termsVersion,
            boolean required, boolean agreed) {
        if (ownerId == null || ownerId <= 0 || termsType == null || termsType.isBlank()
                || termsType.length() > 50 || termsVersion == null || termsVersion.isBlank()
                || termsVersion.length() > 30 || (required && !agreed)) {
            throw new IllegalArgumentException("유효한 점주와 약관 동의 정보가 필요합니다.");
        }
        this.ownerId = ownerId;
        this.termsType = termsType;
        this.termsVersion = termsVersion;
        this.required = required;
        this.agreed = agreed;
    }

    public void revise(boolean required, boolean agreed) {
        if (required && !agreed) {
            throw new IllegalArgumentException("필수 약관에 동의해야 합니다.");
        }
        this.required = required;
        this.agreed = agreed;
        this.withdrawnAt = null;
    }

    public static OwnerTermsAgreement record(Long ownerId, String termsType, String termsVersion,
            boolean required, boolean agreed) {
        return new OwnerTermsAgreement(ownerId, termsType, termsVersion, required, agreed);
    }
}