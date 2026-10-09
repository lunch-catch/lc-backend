package com.launchcatch.owner.contract;

/*
 * 외부 도메인에 전달할 점주 상태 ENUM.
 * 다른 도메인이 점주 내부 엔티티에 직접 의존하지 않도록 함.
 */
public enum OwnerAccountStatus {
    ONBOARDING, ACTIVE, SUSPENDED, WITHDRAWN
}
