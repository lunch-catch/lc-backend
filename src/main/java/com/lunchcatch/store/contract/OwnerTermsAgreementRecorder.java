package com.lunchcatch.store.contract;

public interface OwnerTermsAgreementRecorder {
    /*
     * 호출자는 같은 쓰기 트랜잭션에서 점주 행을 잠그고 ONBOARDING 상태를 확인해야 한다.
     * 전체 필수 약관의 동의 시각과 진행 상태를 함께 저장한 결과를 반환한다.
     */
    TermsAgreementResult recordAgreements(Long ownerId);
}