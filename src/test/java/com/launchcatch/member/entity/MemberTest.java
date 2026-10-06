package com.launchcatch.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.member.contract.MemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberTest {

    @Test
    @DisplayName("새 카카오 회원은 활성 상태로 만든다")
    void 새_카카오_회원은_활성_상태로_만든다() {
        Member member = Member.create("kakao-123", "점심헌터", null);

        assertThat(member.getProviderUserId()).isEqualTo("kakao-123");
        assertThat(member.getNickname()).isEqualTo("점심헌터");
        assertThat(member.getProfileImageUrl()).isNull();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("제공자 회원번호가 없으면 회원을 만들지 않는다")
    void 제공자_회원번호가_없으면_회원을_만들지_않는다() {
        assertThatThrownBy(() -> Member.create(" ", "점심헌터", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerUserId must not be blank");
    }

    @Test
    @DisplayName("닉네임이 없으면 회원을 만들지 않는다")
    void 닉네임이_없으면_회원을_만들지_않는다() {
        assertThatThrownBy(() -> Member.create("kakao-123", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nickname must not be blank");
    }
}
