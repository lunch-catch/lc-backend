package com.launchcatch.store.contract;

import java.util.List;

/*
 * storeId는 초안 생성 전 null이며, version은 진행 상태가 없으면 0이다.
 * resumeStep은 첫 미완료 단계이며 모든 입력 완료 시 MENU, 최종 등록 후에는 null이다.
 * canFinalize는 조회 시점의 안내이므로 최종 등록 요청에서는 조건을 다시 검증해야 한다.
 */
public record StoreOnboardingStatus(Long storeId, String resumeStep, long version,
                                    boolean finalized, boolean canFinalize, List<Step> steps) {
    public StoreOnboardingStatus {
        steps = List.copyOf(steps);
    }

    // MENU만 선택 단계(required=false)이며, 메뉴가 0개면 completed=false여도 최종 등록할 수 있다.
    public record Step(String step, boolean required, boolean completed) { }
}