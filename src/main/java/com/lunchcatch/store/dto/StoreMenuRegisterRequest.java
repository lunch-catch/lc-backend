package com.lunchcatch.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record StoreMenuRegisterRequest(
        @Schema(description = "메뉴명입니다.", example = "제육볶음")
        @NotBlank @Size(max = 100) String name,

        @Schema(description = "메뉴 가격(원 단위)입니다.", example = "9000")
        @NotNull @PositiveOrZero Long price,

        @Schema(description = "메뉴 상세설명입니다. 선택이며 비워 둘 수 있습니다.", example = "매콤한 제육볶음입니다.")
        @Size(max = 500) String description
) {
}
