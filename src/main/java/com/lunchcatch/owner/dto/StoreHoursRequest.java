package com.lunchcatch.owner.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.lunchcatch.store.contract.OwnerStoreSnapshot.BusinessHour;
import com.lunchcatch.store.contract.StoreHoursSaveCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record StoreHoursRequest(
        @PositiveOrZero @Schema(description = "ONBOARDING은 필수, ACTIVE는 생략 또는 null") Long version,
        @NotNull @Size(max = 7) @Schema(description = "영업일만 전달합니다. 누락한 요일은 휴무로 저장하며 빈 배열은 모두 휴무입니다.") List<@NotNull @Valid Hour> businessHours) {
    public StoreHoursSaveCommand toCommand() {
        return new StoreHoursSaveCommand(version, businessHours.stream().map(hour -> new BusinessHour(
                hour.dayOfWeek().name(), hour.openTime(), hour.closeTime(), Boolean.TRUE.equals(hour.isClosed()),
                hour.breakStartTime(), hour.breakEndTime(), hour.lastOrderTime())).toList());
    }

    public record Hour(
            @NotNull DayOfWeek dayOfWeek,
            @Schema(description = "생략 또는 null이면 영업일(false)") Boolean isClosed,
            @JsonFormat(pattern = "HH:mm:ss", lenient = OptBoolean.FALSE) LocalTime openTime,
            @JsonFormat(pattern = "HH:mm:ss", lenient = OptBoolean.FALSE) LocalTime closeTime,
            @JsonFormat(pattern = "HH:mm:ss", lenient = OptBoolean.FALSE) LocalTime breakStartTime,
            @JsonFormat(pattern = "HH:mm:ss", lenient = OptBoolean.FALSE) LocalTime breakEndTime,
            @JsonFormat(pattern = "HH:mm:ss", lenient = OptBoolean.FALSE) LocalTime lastOrderTime) { }
}
