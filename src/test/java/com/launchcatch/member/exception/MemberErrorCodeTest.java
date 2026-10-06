package com.launchcatch.member.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MemberErrorCodeTest {

    @Test
    @DisplayName("회원 오류 코드는 팀 API 계약의 연속 번호와 상태를 사용한다")
    void 회원_오류_코드는_계약을_따른다() {
        assertThat(MemberErrorCode.ALREADY_WITHDRAWN.getCode()).isEqualTo("MEMBER-001");
        assertThat(MemberErrorCode.ALREADY_WITHDRAWN.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(MemberErrorCode.ONBOARDING_ALREADY_COMPLETED.getCode()).isEqualTo("MEMBER-002");
        assertThat(MemberErrorCode.ONBOARDING_ALREADY_COMPLETED.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED.getCode()).isEqualTo("MEMBER-003");
        assertThat(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
