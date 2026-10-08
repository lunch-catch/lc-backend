package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.launchcatch.member.dto.MemberOnboardingRequest;
import com.launchcatch.member.dto.MemberResponse;
import com.launchcatch.member.dto.MemberUpdateRequest;
import com.launchcatch.member.entity.AgeGroup;
import com.launchcatch.member.entity.Gender;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberProfileServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberProfileRepository memberProfileRepository;

    @Spy
    private Clock clock = CLOCK;

    @InjectMocks
    private MemberProfileService memberProfileService;

    @Test
    @DisplayName("온보딩은 프로필을 만들고 동의와 완료 시각을 저장한다")
    void 온보딩은_프로필을_만들고_동의와_완료_시각을_저장한다() throws Exception {
        Member member = member();
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberProfileRepository.save(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MemberResponse response = memberProfileService.completeOnboarding(1L,
                new MemberOnboardingRequest(Gender.FEMALE, AgeGroup.AGE_20S, true, false));

        assertThat(response.onboardingCompleted()).isTrue();
        assertThat(response.feedAvailable()).isTrue();
        assertThat(response.profile()).isEqualTo(new MemberResponse.Profile(Gender.FEMALE, AgeGroup.AGE_20S));
        assertThat(response.consents().locationOptIn()).isTrue();
        assertThat(response.consents().notificationOptIn()).isFalse();
    }

    @Test
    @DisplayName("완료한 회원은 온보딩을 다시 할 수 없다")
    void 완료한_회원은_온보딩을_다시_할_수_없다() throws Exception {
        Member member = member();
        MemberProfile profile = MemberProfile.create(member);
        profile.completeOnboarding(Gender.MALE, AgeGroup.AGE_30S, java.time.LocalDateTime.now(CLOCK));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> memberProfileService.completeOnboarding(1L,
                new MemberOnboardingRequest(Gender.FEMALE, AgeGroup.AGE_20S, true, false)))
                .isInstanceOf(MemberException.class);
    }

    @Test
    @DisplayName("프로필이 없는 회원도 내 정보를 조회할 수 있다")
    void 프로필이_없는_회원도_내_정보를_조회할_수_있다() throws Exception {
        Member member = member();
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MemberResponse response = memberProfileService.getMyProfile(1L);

        assertThat(response.profile()).isNull();
        assertThat(response.onboardingCompleted()).isFalse();
        assertThat(response.feedAvailable()).isFalse();
    }

    @Test
    @DisplayName("온보딩 전 프로필 행은 내 정보 프로필에 노출하지 않는다")
    void 온보딩_전_프로필_행은_내_정보_프로필에_노출하지_않는다() throws Exception {
        Member member = member();
        MemberProfile.create(member);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MemberResponse response = memberProfileService.getMyProfile(1L);

        assertThat(response.profile()).isNull();
        assertThat(response.onboardingCompleted()).isFalse();
    }

    @Test
    @DisplayName("온보딩을 마치지 않았으면 위치에 동의했어도 피드를 쓸 수 없다")
    void 온보딩을_마치지_않았으면_위치에_동의했어도_피드를_쓸_수_없다() throws Exception {
        Member member = member();
        member.updateProfile(null, null, true, java.time.LocalDateTime.of(2026, 10, 8, 9, 0));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MemberResponse response = memberProfileService.getMyProfile(1L);

        assertThat(response.consents().locationOptIn()).isTrue();
        assertThat(response.onboardingCompleted()).isFalse();
        assertThat(response.feedAvailable()).isFalse();
    }

    @Test
    @DisplayName("내 정보 수정은 전달된 필드만 반영한다")
    void 내_정보_수정은_전달된_필드만_반영한다() throws Exception {
        Member member = member();
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MemberResponse response = memberProfileService.updateMyProfile(1L,
                new MemberUpdateRequest("새닉네임", true, true));

        assertThat(response.nickname()).isEqualTo("새닉네임");
        assertThat(response.consents().notificationOptIn()).isTrue();
        assertThat(response.consents().locationOptIn()).isTrue();
        // 온보딩을 마치지 않은 회원이라 위치에 동의해도 피드는 열리지 않는다
        assertThat(response.feedAvailable()).isFalse();
    }

    @Test
    @DisplayName("수정할 필드가 없으면 거부한다")
    void 수정할_필드가_없으면_거부한다() {
        assertThatThrownBy(() -> memberProfileService.updateMyProfile(1L,
                new MemberUpdateRequest(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at least one field must be provided");
    }

    /*
     * 인증을 통과한 토큰의 회원이 DB 에 없는 상태다.
     * 사용자 입력 오류가 아니라 서버 쪽 전제가 깨진 것이라 IllegalStateException 으로 둔다.
     */
    @Test
    @DisplayName("인증된 회원이 없으면 내 정보를 조회할 수 없다")
    void 인증된_회원이_없으면_내_정보를_조회할_수_없다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberProfileService.getMyProfile(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authenticated member does not exist");
    }

    private Member member() throws Exception {
        Member member = Member.create("kakao-123", "점심헌터", null);
        setField(member, "id", 1L);
        setField(member, "createdAt", java.time.LocalDateTime.of(2026, 10, 1, 9, 0));
        return member;
    }

    private void setField(Member member, String fieldName, Object value) throws Exception {
        Field field = member.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(member, value);
    }
}
