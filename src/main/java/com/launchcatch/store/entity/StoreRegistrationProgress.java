package com.launchcatch.store.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_registration_progress", uniqueConstraints = {
        @UniqueConstraint(name = "uk_store_registration_progress_owner", columnNames = "owner_id"),
        @UniqueConstraint(name = "uk_store_registration_progress_store", columnNames = "store_id")})
@AttributeOverride(name = "id", column = @Column(name = "store_registration_progress_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreRegistrationProgress extends BaseTimeEntity {
    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;
    @Column(name = "store_id")
    private Long storeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "resume_step", nullable = false, length = 32)
    private StoreRegistrationStep resumeStep;
    @Column(nullable = false)
    private long version;

    private StoreRegistrationProgress(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("유효한 점주 ID가 필요합니다.");
        }
        this.ownerId = ownerId;
        this.resumeStep = StoreRegistrationStep.TERMS;
        this.version = 0;
    }

    public static StoreRegistrationProgress start(Long ownerId) {
        return new StoreRegistrationProgress(ownerId);
    }

    // 호출자는 진행 상태 행을 잠그고 완료한 이전 단계의 수정 후에도 필수 조건이 충족되는지 검증해야 한다.
    public void reviseStep(long expectedVersion, StoreRegistrationStep revisedStep) {
        if (expectedVersion < 0 || revisedStep == null) {
            throw new IllegalArgumentException("유효한 버전과 수정 단계가 필요합니다.");
        }
        if (version != expectedVersion) {
            throw new StoreException(StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        if (revisedStep.ordinal() > resumeStep.ordinal()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        version = Math.incrementExact(version);
    }

    // 호출자는 진행 상태 행을 잠그고 단계 입력값 검증을 완료해야 한다.
    public void completeStep(long expectedVersion, StoreRegistrationStep completedStep, Long registeredStoreId) {
        if (expectedVersion < 0 || completedStep == null) {
            throw new IllegalArgumentException("유효한 버전과 완료 단계가 필요합니다.");
        }
        if (version != expectedVersion) {
            throw new StoreException(StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        if (resumeStep != completedStep) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        if (completedStep == StoreRegistrationStep.STORE_INFO) {
            if (registeredStoreId == null || registeredStoreId <= 0) {
                throw new IllegalArgumentException("가게 정보 등록과 사업자 검증을 완료한 가게 ID가 필요합니다.");
            }
        } else if (registeredStoreId != null) {
            throw new IllegalArgumentException("가게 연결은 가게 정보 단계 완료 시에만 가능합니다.");
        }
        long nextVersion = Math.incrementExact(version);
        if (completedStep == StoreRegistrationStep.STORE_INFO) {
            storeId = registeredStoreId;
        }
        resumeStep = completedStep.afterSave();
        version = nextVersion;
    }
}