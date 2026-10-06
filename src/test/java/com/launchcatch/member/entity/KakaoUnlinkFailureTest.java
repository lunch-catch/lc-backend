package com.launchcatch.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoUnlinkFailureTest {

    @Test
    @DisplayName("unlink 실패는 탈퇴 시점의 카카오 회원번호를 복사해 보관한다")
    void unlink_실패는_카카오_회원번호를_복사해_보관한다() {
        Member member = Member.create("kakao-123", "점심헌터", null);

        KakaoUnlinkFailure failure = KakaoUnlinkFailure.create(member);

        assertThat(failure.getMember()).isSameAs(member);
        assertThat(failure.getProviderUserId()).isEqualTo("kakao-123");
        assertThat(failure.getAttemptCount()).isZero();
        assertThat(failure.isResolved()).isFalse();
    }

    @Test
    @DisplayName("회원 없이 unlink 실패 행을 만들지 않는다")
    void 회원_없이_unlink_실패_행을_만들지_않는다() {
        assertThatThrownBy(() -> KakaoUnlinkFailure.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("member must not be null");
    }
}
