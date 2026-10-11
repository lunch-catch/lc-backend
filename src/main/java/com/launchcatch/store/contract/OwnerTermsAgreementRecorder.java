package com.launchcatch.store.contract;

import java.util.List;

public interface OwnerTermsAgreementRecorder {
    /*
     * 호출자는 같은 쓰기 트랜잭션에서 점주 행을 잠그고 ONBOARDING 상태를 확인해야 한다.
     * 필수 약관을 검증하고 새 동의 내역 전체를 저장하며, 완료 여부는 저장 내역으로 계산한다.
     */
    boolean recordAgreements(Long ownerId, List<TermsAgreementCommand> agreements);
}