package com.launchcatch.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberProfileTest {

    @Test
    @DisplayName("회원 프로필은 회원과 일대일로 연결한다")
    void 회원_프로필은_회원과_일대일로_연결한다() {
        Member member = Member.create("kakao-123", "점심헌터", null);

        MemberProfile profile = MemberProfile.create(member);

        assertThat(profile.getMember()).isSameAs(member);
        assertThat(member.getProfile()).isSameAs(profile);
        assertThat(profile.getGender()).isNull();
        assertThat(profile.getAgeGroup()).isNull();
    }

    @Test
    @DisplayName("회원 없이 프로필을 만들지 않는다")
    void 회원_없이_프로필을_만들지_않는다() {
        assertThatThrownBy(() -> MemberProfile.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("member must not be null");
    }
}
