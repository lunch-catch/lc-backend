package com.lunchcatch.campaign.dto;

import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.TargetGender;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/*
 * 노출 대상(3단계)과 하루 예산·집행 기간(4단계)을 같은 PATCH로 받는다(campaign-api-spec.md).
 * 보낸 필드만 검사하고 바꿔서 여섯 필드 모두 선택이다. 한쪽 묶음 중 하나라도 오면 그
 * 묶음은 다 와야 한다는 조건과, 캠페인 상태별로 허용되는 묶음은
 * CampaignTargetAudienceService가 검사한다(#330).
 */
public record CampaignUpdateRequest(
        @Schema(description = "노출 반경(m)입니다. 보내면 targetGender/targetAgeGroups도 필수입니다.",
                example = "1000")
        Integer targetRadius,

        @Schema(description = "노출 대상 성별입니다. 보내면 targetRadius/targetAgeGroups도 필수입니다.",
                example = "ALL")
        TargetGender targetGender,

        @Schema(description = "노출 대상 연령대입니다. 보내면 targetRadius/targetGender도 필수입니다. "
                + "1개 이상, 중복 없이 보냅니다.")
        List<AgeGroup> targetAgeGroups,

        @Schema(description = "하루 예산(포인트)입니다. DRAFT에서 보내면 startDate/endDate도 필수입니다. "
                + "ACTIVE에서는 이 필드만 보낼 수 있습니다.")
        Long dailyBudget,

        @Schema(description = "집행 시작일입니다. DRAFT에서만 보낼 수 있고 보내면 dailyBudget/endDate도 필수입니다.")
        LocalDate startDate,

        @Schema(description = "집행 종료일입니다. DRAFT에서만 보낼 수 있고 보내면 dailyBudget/startDate도 필수입니다.")
        LocalDate endDate
) {
}
