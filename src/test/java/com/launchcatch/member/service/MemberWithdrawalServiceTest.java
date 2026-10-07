package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalServiceTest {
    @Mock KakaoIdTokenExchanger kakaoIdTokenExchanger;
    @Mock MemberRepository memberRepository;
    @Mock MemberProfileRepository memberProfileRepository;
    @Mock MemberTokenService memberTokenService;
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    @InjectMocks MemberWithdrawalService memberWithdrawalService;

    @Test
    void 재인증한_회원은_개인정보와_프로필을_정리하고_탈퇴한다() throws Exception {
        Member member = member();
        MemberProfile profile = MemberProfile.create(member);
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(new KakaoIdentity("kakao-1", "닉네임", null));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.of(profile));

        memberWithdrawalService.withdraw(1L, "code", "state");

        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getNickname()).isEqualTo("탈퇴한 회원");
        assertThat(member.getProfileImageUrl()).isNull();
        assertThat(member.getLastLoginAt()).isNull();
        assertThat(member.isNotificationOptIn()).isFalse();
        assertThat(member.isLocationOptIn()).isFalse();
        verify(memberProfileRepository).delete(profile);
        verify(memberTokenService).revoke(1L);
    }

    @Test
    void 재인증한_카카오_계정이_다르면_탈퇴를_거부한다() throws Exception {
        Member member = member();
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(new KakaoIdentity("other", "닉네임", null));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(1L, "code", "state"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 이미_탈퇴한_회원은_다시_탈퇴할_수_없다() throws Exception {
        Member member = member();
        setField(member, "status", MemberStatus.WITHDRAWN);
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(new KakaoIdentity("kakao-1", "닉네임", null));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(1L, "code", "state"))
                .isInstanceOf(MemberException.class);
    }

    private Member member() throws Exception {
        Member member = Member.create("kakao-1", "닉네임", "https://image");
        setField(member, "id", 1L);
        return member;
    }

    private void setField(Member member, String name, Object value) throws Exception {
        Field field = name.equals("id") ? member.getClass().getSuperclass().getDeclaredField(name)
                : Member.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(member, value);
    }
}
