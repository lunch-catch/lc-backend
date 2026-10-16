package com.launchcatch.store.contract;

import java.util.List;

public interface OwnerTermsAgreementRecorder {
    /*
     * 호출자는 같은 쓰기 트랜잭션에서 점주 행을 잠그고 ONBOARDING 상태를 확인해야 한다.
     * 필수 약관을 검증하고 전체 입력과 진행 상태를 함께 저장한 결과를 반환한다.
     */
    TermsAgreementResult recordAgreements(Long ownerId, long expectedVersion, List<TermsAgreementCommand> agreements);
}