package com.launchcatch.campaign.dto;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.TargetGender;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record CampaignUpdateResponse(
        @Schema(description = "수정한 캠페인 ID입니다.", example = "501")
        Long campaignId,

        @Schema(description = "노출 반경(m)입니다. 수정 뒤의 현재 값입니다.", example = "1000")
        Integer targetRadius,

        @Schema(description = "노출 대상 성별입니다. 수정 뒤의 현재 값입니다.", example = "ALL")
        TargetGender targetGender,

        @Schema(description = "노출 대상 연령대입니다. 수정 뒤의 현재 값입니다.")
        List<AgeGroup> targetAgeGroups,

        @Schema(description = "하루 예산(포인트)입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        Long dailyBudget,

        @Schema(description = "집행 시작일입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        LocalDate startDate,

        @Schema(description = "집행 종료일입니다. 아직 한 번도 설정하지 않았으면 null입니다.")
        LocalDate endDate
) {
}
