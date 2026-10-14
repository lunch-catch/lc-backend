package com.launchcatch.store.service;

import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StoreRegistrationProgressService {
    private final StoreRegistrationProgressRepository progresses;

    /*
     * 호출자는 점주 행을 먼저 잠그고 인증과 현재 상태를 확인해야 한다.
     * 아직 없는 진행 상태의 동시 생성도 같은 점주 행 잠금으로 직렬화한다.
     */
    public StoreRegistrationProgressSnapshot initialize(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("유효한 점주 ID가 필요합니다.");
        }
        var progress = progresses.findByOwnerIdForUpdate(ownerId)
                .orElseGet(() -> progresses.saveAndFlush(StoreRegistrationProgress.start(ownerId)));
        return snapshot(progress);
    }

    /*
     * 호출자가 업무별 필수 조건을 검증하고 단계 데이터와 같은 트랜잭션에서 호출한다.
     * 버전 충돌과 전환 실패 시 단계 데이터 저장도 함께 롤백한다.
     */
    public StoreRegistrationProgressSnapshot completeStep(Long ownerId, long expectedVersion,
            StoreRegistrationStep completedStep, Long registeredStoreId) {
        if (ownerId == null || ownerId <= 0 || expectedVersion < 0 || completedStep == null) {
            throw new IllegalArgumentException("유효한 점주 ID, 버전과 완료 단계가 필요합니다.");
        }
        var progress = progresses.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        if (completedStep == StoreRegistrationStep.STORE && registeredStoreId != null
                && progresses.countDraftStoreOwnedBy(ownerId, registeredStoreId) != 1) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        }
        progress.completeStep(expectedVersion, completedStep, registeredStoreId);
        progresses.saveAndFlush(progress);
        return snapshot(progress);
    }

    /*
     * 완료한 영업시간과 이미지 수정은 재개 단계를 유지하고 버전만 증가시킨다.
     * 호출자는 수정 데이터의 필수 조건을 검증하고 같은 트랜잭션에서 저장해야 한다.
     */
    public StoreRegistrationProgressSnapshot reviseCompletedStep(Long ownerId, long expectedVersion,
            StoreRegistrationStep revisedStep) {
        if (ownerId == null || ownerId <= 0 || expectedVersion < 0 || revisedStep == null) {
            throw new IllegalArgumentException("유효한 점주 ID, 버전과 수정 단계가 필요합니다.");
        }
        var progress = progresses.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        progress.reviseCompletedStep(expectedVersion, revisedStep);
        progresses.saveAndFlush(progress);
        return snapshot(progress);
    }

    private StoreRegistrationProgressSnapshot snapshot(StoreRegistrationProgress progress) {
        return new StoreRegistrationProgressSnapshot(progress.getStoreId(), progress.getResumeStep(), progress.getVersion());
    }
}