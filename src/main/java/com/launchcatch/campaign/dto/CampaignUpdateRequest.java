package com.launchcatch.campaign.dto;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.TargetGender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/*
 * 노출 대상(3단계)과 하루 예산·집행 기간(4단계)을 같은 PATCH로 받는다(campaign-api-spec.md).
 * 이 PR은 노출 대상만 구현해서 targetRadius/targetGender/targetAgeGroups 셋을 항상
 * 요구하고, dailyBudget/startDate/endDate는 아직 지원하지 않아 와 있으면 거부한다(#253).
 */
public record CampaignUpdateRequest(
        @Schema(description = "노출 반경(m)입니다.", example = "1000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer targetRadius,

        @Schema(description = "노출 대상 성별입니다.", example = "ALL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull TargetGender targetGender,

        @Schema(description = "노출 대상 연령대입니다. 1개 이상, 중복 없이 보냅니다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<AgeGroup> targetAgeGroups,

        @Schema(description = "하루 예산(포인트)입니다. 아직 지원하지 않아 보내면 거부됩니다.")
        Long dailyBudget,

        @Schema(description = "집행 시작일입니다. 아직 지원하지 않아 보내면 거부됩니다.")
        LocalDate startDate,

        @Schema(description = "집행 종료일입니다. 아직 지원하지 않아 보내면 거부됩니다.")
        LocalDate endDate
) {
}
