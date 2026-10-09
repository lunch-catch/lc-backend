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

    @Test
    @DisplayName("탈퇴해도 제공자 회원번호는 남긴다")
    void 탈퇴해도_제공자_회원번호는_남긴다() {
        Member member = Member.create("kakao-123", "점심헌터", "https://image");

        member.withdraw(LocalDateTime.of(2026, 10, 8, 9, 0));

        assertThat(member.getProviderUserId()).isEqualTo("kakao-123");
        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getNickname()).isEqualTo("탈퇴한 회원");
        assertThat(member.getProfileImageUrl()).isNull();
        assertThat(member.getLastLoginAt()).isNull();
    }

    @Test
    @DisplayName("같은 동의 값을 다시 보내면 동의 시각이 바뀌지 않는다")
    void 같은_동의_값을_다시_보내면_동의_시각이_바뀌지_않는다() {
        Member member = Member.create("kakao-123", "점심헌터", null);
        LocalDateTime first = LocalDateTime.of(2026, 10, 1, 9, 0);
        member.completeOnboarding(true, true, first);

        member.updateProfile(null, true, true, LocalDateTime.of(2026, 10, 8, 9, 0));

        assertThat(member.getNotificationOptInAt()).isEqualTo(first);
        assertThat(member.getLocationOptInAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("동의를 바꾸면 동의 시각과 철회 시각이 함께 바뀐다")
    void 동의를_바꾸면_동의_시각과_철회_시각이_함께_바뀐다() {
        Member member = Member.create("kakao-123", "점심헌터", null);
        member.completeOnboarding(true, true, LocalDateTime.of(2026, 10, 1, 9, 0));
        LocalDateTime later = LocalDateTime.of(2026, 10, 8, 9, 0);

        member.updateProfile(null, false, null, later);

        assertThat(member.isNotificationOptIn()).isFalse();
        assertThat(member.getNotificationOptInAt()).isNull();
        assertThat(member.getNotificationWithdrawnAt()).isEqualTo(later);
        assertThat(member.isLocationOptIn()).isTrue();
    }

    private void setField(Member member, String fieldName, Object value) throws Exception {
        Field field = Member.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(member, value);
    }
}
