package com.lunchcatch.store.contract;

import java.time.LocalDateTime;
import java.util.List;

// 가게 생성 전에도 점주의 약관과 진행 정보를 반환하는 통합 조회 계약이다.
public record OwnerStoreOverview(OwnerStoreSnapshot store, Onboarding onboarding) {
    public record Onboarding(String currentStep, String resumeStep, long version, boolean canComplete,
            TermsAgreement termsAgreement, List<Step> steps) {
        public Onboarding {
            steps = List.copyOf(steps);
        }
    }
    public record TermsAgreement(String termsType, String termsVersion, boolean required, boolean agreed,
            LocalDateTime agreedAt, LocalDateTime recordedAt, LocalDateTime withdrawnAt) { }
    public record Step(String step, boolean optional, boolean hasSavedData, boolean completed) { }
}
