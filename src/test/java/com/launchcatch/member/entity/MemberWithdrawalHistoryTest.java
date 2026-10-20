package com.launchcatch.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberWithdrawalHistoryTest {

    @Test
    void 회원의_번호를_비워도_탈퇴_이력의_번호와_정지_시각은_남는다() {
        Member member = Member.create("kakao-1", "닉네임", null);
        ReflectionTestUtils.setField(member, "id", 1L);
        LocalDateTime suspendedAt = LocalDateTime.of(2026, 10, 1, 13, 0);
        ReflectionTestUtils.setField(member, "suspendedAt", suspendedAt);
        LocalDateTime withdrawnAt = suspendedAt.plusDays(1);

        MemberWithdrawalHistory history = MemberWithdrawalHistory.create(member, withdrawnAt);
        member.withdraw(withdrawnAt);

        assertThat(history.getMemberId()).isEqualTo(1L);
        assertThat(history.getProviderUserId()).isEqualTo("kakao-1");
        assertThat(history.getSuspendedAt()).isEqualTo(suspendedAt);
        assertThat(history.getWithdrawnAt()).isEqualTo(withdrawnAt);
        assertThat(member.getProviderUserId()).isNull();
    }

    @Test
    void 이미_번호를_비운_회원의_이력은_만들지_않는다() {
        Member member = Member.create("kakao-1", "닉네임", null);
        member.withdraw(LocalDateTime.of(2026, 10, 1, 13, 0));

        assertThatThrownBy(() -> MemberWithdrawalHistory.create(member, LocalDateTime.of(2026, 10, 1, 13, 0)))
                .isInstanceOf(IllegalStateException.class);
    }
}
