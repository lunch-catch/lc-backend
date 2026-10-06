package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberQueryServiceImplTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private Member member;

    @Mock
    private MemberProfile memberProfile;

    @InjectMocks
    private MemberQueryServiceImpl memberQueryService;

    @Test
    @DisplayName("회원 조회는 다른 도메인에 필요한 상태와 동의 정보만 공개한다")
    void 회원_조회는_공개_정보를_반환한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getId()).thenReturn(1L);
        when(member.getNickname()).thenReturn("점심헌터");
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.getProfile()).thenReturn(memberProfile);
        when(memberProfile.isOnboardingCompleted()).thenReturn(true);
        when(member.isNotificationOptIn()).thenReturn(true);
        when(member.isLocationOptIn()).thenReturn(false);

        Optional<MemberInfo> result = memberQueryService.findById(1L);

        assertThat(result).contains(new MemberInfo(1L, "점심헌터", MemberStatus.ACTIVE, true, true, false));
    }

    @Test
    @DisplayName("프로필이 없으면 온보딩 미완료로 반환한다")
    void 프로필이_없으면_온보딩_미완료로_반환한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getId()).thenReturn(1L);
        when(member.getNickname()).thenReturn("점심헌터");
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.isNotificationOptIn()).thenReturn(false);
        when(member.isLocationOptIn()).thenReturn(false);

        Optional<MemberInfo> result = memberQueryService.findById(1L);

        assertThat(result).contains(new MemberInfo(1L, "점심헌터", MemberStatus.ACTIVE, false, false, false));
    }

    @Test
    @DisplayName("없는 회원은 빈 결과를 반환한다")
    void 없는_회원은_빈_결과를_반환한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        Optional<MemberInfo> result = memberQueryService.findById(1L);

        assertThat(result).isEmpty();
    }
}
