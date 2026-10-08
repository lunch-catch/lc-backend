package com.launchcatch.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.member.contract.MemberStatus;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
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

    @Test
    @DisplayName("정지 이력이 있으면 탈퇴 회원을 재활성화하지 않는다")
    void 정지_이력이_있으면_재활성화하지_않는다() throws Exception {
        Member member = Member.create("kakao-123", "점심헌터", null);
        setField(member, "status", MemberStatus.WITHDRAWN);
        setField(member, "suspendedAt", LocalDateTime.of(2026, 10, 1, 9, 0));

        assertThatThrownBy(() -> member.reactivate("새닉네임", null, LocalDateTime.of(2026, 10, 6, 9, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("member with suspension history cannot reactivate");
    }

    private void setField(Member member, String fieldName, Object value) throws Exception {
        Field field = Member.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(member, value);
    }
}
