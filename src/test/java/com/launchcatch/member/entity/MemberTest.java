package com.launchcatch.member.entity;

import static org.assertj.core.api.Assertions.assertThat;

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
}
