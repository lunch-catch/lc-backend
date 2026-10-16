package com.launchcatch.adserving.impression.dto;

import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionValidity;
import com.launchcatch.adserving.impression.entity.InvalidReason;
import io.swagger.v3.oas.annotations.media.Schema;

public record ImpressionResponse(
        @Schema(description = "VALID(유효 노출), INVALID(무효 노출)")
        ImpressionValidity validity,
        @Schema(description = "INVALID 일 때 사유. VALID 면 null", nullable = true)
        InvalidReason invalidReason
) {

    public static ImpressionResponse of(ImpressionLog log) {
        return new ImpressionResponse(log.getValidity(), log.getInvalidReason());
    }
}
