package com.launchcatch.owner.dto;

import com.launchcatch.store.contract.StoreOnboardingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OwnerStoreOnboardingResponse(
        @Schema(description = "본인 가게 ID. 초안 생성 전에는 null입니다.") Long storeId,
        @Schema(description = "지금 진행할 첫 미완료 단계. 필수 입력 완료 시 MENU, 최종 등록 후 null입니다.") String resumeStep,
        @Schema(description = "다음 저장 요청에 사용할 버전. 진행 상태가 없으면 0입니다.") long version,
        @Schema(description = "가게 최종 등록 완료 여부입니다.") boolean finalized,
        @Schema(description = "최종 등록 요청 가능 여부. 메뉴 0개도 허용하며 등록 완료 후에는 false입니다.") boolean canFinalize,
        @Schema(description = "입력 순서대로 나열한 여섯 단계의 완료 여부입니다.") List<Step> steps) {
    public OwnerStoreOnboardingResponse {
        steps = List.copyOf(steps);
    }

    public static OwnerStoreOnboardingResponse from(StoreOnboardingStatus status) {
        return new OwnerStoreOnboardingResponse(status.storeId(), status.resumeStep(), status.version(),
                status.finalized(), status.canFinalize(), status.steps().stream()
                        .map(step -> new Step(step.step(), step.required(), step.completed())).toList());
    }

    public record Step(
            @Schema(description = "TERMS, STORE_INFO, LOCATION, HOURS, IMAGES, MENU 중 하나입니다.") String step,
            @Schema(description = "필수 단계 여부. MENU만 false입니다.") boolean required,
            @Schema(description = "저장된 업무 데이터가 해당 단계의 완료 조건을 충족하는지 나타냅니다.") boolean completed) { }
}