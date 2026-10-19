package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.repository.MemberRepository;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

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

    @ParameterizedTest
    @EnumSource(MemberStatus.class)
    void 현재_상태_조회는_회원과_프로필을_만들지_않고_상태만_반환한다(MemberStatus status) {
        when(memberRepository.findStatusById(1L)).thenReturn(Optional.of(status));

        assertThat(memberQueryService.findCurrentStatus(1L)).contains(status);
        verifyNoInteractions(member, memberProfile);
    }

    @Test
    void 없는_회원의_현재_상태는_빈_결과를_반환한다() {
        when(memberRepository.findStatusById(1L)).thenReturn(Optional.empty());

        assertThat(memberQueryService.findCurrentStatus(1L)).isEmpty();
    }

    @Test
    void 현재_상태_조회_장애는_그대로_전달한다() {
        var failure = new QueryTimeoutException("member unavailable");
        when(memberRepository.findStatusById(1L)).thenThrow(failure);

        assertThatThrownBy(() -> memberQueryService.findCurrentStatus(1L)).isSameAs(failure);
    }

    @Test
    @DisplayName("회원 조회는 다른 도메인에 필요한 상태와 동의 정보만 공개한다")
    void 회원_조회는_공개_정보를_반환한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        stubOnboardedMember();

        Optional<MemberInfo> result = memberQueryService.findById(1L);

        assertThat(result).contains(onboardedMemberInfo());
    }

    @Test
    @DisplayName("프로필이 없으면 온보딩 미완료로 반환하고 성별과 연령대는 비운다")
    void 프로필이_없으면_온보딩_미완료로_반환한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getId()).thenReturn(1L);
        when(member.getNickname()).thenReturn("점심헌터");
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.isNotificationOptIn()).thenReturn(false);
        when(member.isLocationOptIn()).thenReturn(false);

        Optional<MemberInfo> result = memberQueryService.findById(1L);

        assertThat(result).contains(
                new MemberInfo(1L, "점심헌터", MemberStatus.ACTIVE, false, false, false, null, null, null, null));
    }

    @Test
    @DisplayName("없는 회원은 빈 결과를 반환한다")
    void 없는_회원은_빈_결과를_반환한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        Optional<MemberInfo> result = memberQueryService.findById(1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("묶음 조회는 프로필을 함께 읽어 성별과 연령대를 담아 반환한다")
    void 묶음_조회는_성별과_연령대를_반환한다() {
        when(memberRepository.findAllWithProfileByIdIn(List.of(1L))).thenReturn(List.of(member));
        stubOnboardedMember();

        List<MemberInfo> result = memberQueryService.findAllById(List.of(1L));

        assertThat(result).containsExactly(onboardedMemberInfo());
    }

    @Test
    @DisplayName("묶음 조회는 빈 목록이면 저장소를 부르지 않고 빈 결과를 반환한다")
    void 묶음_조회는_빈_목록이면_빈_결과를_반환한다() {
        List<MemberInfo> result = memberQueryService.findAllById(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("묶음 조회는 상한을 넘는 목록을 거부한다")
    void 묶음_조회는_상한을_넘으면_예외를_던진다() {
        List<Long> memberIds = Collections.nCopies(MemberQueryService.MAX_FIND_ALL_SIZE + 1, 1L);

        assertThatThrownBy(() -> memberQueryService.findAllById(memberIds))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(memberRepository);
    }

    private void stubOnboardedMember() {
        when(member.getId()).thenReturn(1L);
        when(member.getNickname()).thenReturn("점심헌터");
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.getProfile()).thenReturn(memberProfile);
        when(memberProfile.isOnboardingCompleted()).thenReturn(true);
        when(memberProfile.getLatitude()).thenReturn(new BigDecimal("37.5665000"));
        when(memberProfile.getLongitude()).thenReturn(new BigDecimal("126.9780000"));
        when(memberProfile.getGender()).thenReturn(Gender.FEMALE);
        when(memberProfile.getAgeGroup()).thenReturn(AgeGroup.AGE_30S);
        when(member.isNotificationOptIn()).thenReturn(true);
        when(member.isLocationOptIn()).thenReturn(false);
    }

    private MemberInfo onboardedMemberInfo() {
        return new MemberInfo(
                1L,
                "점심헌터",
                MemberStatus.ACTIVE,
                true,
                true,
                false,
                new BigDecimal("37.5665000"),
                new BigDecimal("126.9780000"),
                Gender.FEMALE,
                AgeGroup.AGE_30S
        );
    }
}
