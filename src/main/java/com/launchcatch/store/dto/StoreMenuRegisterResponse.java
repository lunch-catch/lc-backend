package com.launchcatch.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StoreMenuRegisterResponse(
        @Schema(description = "등록된 메뉴의 식별자입니다. 이미지 업로드 요청에 그대로 보냅니다.")
        Long menuId,

        @Schema(description = "이 메뉴가 몇 번째로 표시되는지입니다.", example = "1")
        int sortOrder
) {
}
