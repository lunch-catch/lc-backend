package com.launchcatch.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원 정보 부분 수정. 생략하거나 null로 지정한 필드는 유지하며 적어도 한 필드가 필요합니다.")
public record MemberUpdateRequest(
        @Schema(description = "닉네임. 1~20자이며 공백만 입력할 수 없습니다.", example = "점심헌터", minLength = 1, maxLength = 20, nullable = true)
        @Size(min = 1, max = 20, message = "닉네임은 1자 이상 20자 이하로 입력해 주세요.")
        @Pattern(regexp = ".*\\S.*", message = "닉네임은 공백만 입력할 수 없습니다.") String nickname,
        @Schema(description = "알림 수신 동의 여부입니다.", example = "false", nullable = true)
        Boolean notificationOptIn,
        @Schema(description = "위치정보 수집 동의 여부입니다.", example = "true", nullable = true)
        Boolean locationOptIn
) {

    public boolean hasUpdate() {
        return nickname != null || notificationOptIn != null || locationOptIn != null;
    }
}
