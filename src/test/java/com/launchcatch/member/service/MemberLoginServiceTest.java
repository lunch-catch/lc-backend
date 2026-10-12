package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MemberLoginServiceTest {

    @Mock
    private KakaoIdTokenExchanger kakaoIdTokenExchanger;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private MemberProfileRepository memberProfileRepository;
    @Mock
    private KakaoUnlinkFailureRepository kakaoUnlinkFailureRepository;
    @Mock
    private MemberTokenService memberTokenService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private MemberLoginService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T04:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new MemberLoginService(
                kakaoIdTokenExchanger,
                memberRepository,
                memberProfileRepository,
                kakaoUnlinkFailureRepository,
                memberTokenService,
                transactionTemplate,
                clock);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("처음 카카오 로그인하면 회원을 만들고 온보딩 여부를 반환한다")
    void 최초_로그인() throws Exception {
        KakaoIdentity identity = new KakaoIdentity("kakao-1", "점심헌터", null);
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(identity);
        when(memberRepository.findByProviderUserId("kakao-1")).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> {
            Member savedMember = invocation.getArgument(0);
            setId(savedMember, 1L);
            return savedMember;
        });
        when(memberTokenService.issue(any(Member.class)))
                .thenReturn(new MemberTokenService.TokenPair("access", "refresh", 1L));
        when(memberProfileRepository.existsByMember_IdAndOnboardingCompletedAtIsNotNull(1L)).thenReturn(false);

        MemberLoginService.LoginResult result = service.login("code", "state");

        assertThat(result.member().getProviderUserId()).isEqualTo("kakao-1");
        assertThat(result.newMember()).isTrue();
        assertThat(result.onboardingCompleted()).isFalse();
        assertThat(result.tokenPair().accessToken()).isEqualTo("access");
        assertThat(result.member().getLastLoginAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 13, 0));
    }

    @Test
    @DisplayName("활성 회원은 카카오 프로필을 덮어쓰지 않고 로그인 시각만 갱신한다")
    void 기존_활성_회원_로그인() throws Exception {
        KakaoIdentity identity = new KakaoIdentity("kakao-1", "새닉네임", "https://new-image");
        Member member = Member.create("kakao-1", "기존닉네임", "https://old-image");
        setId(member, 1L);
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(identity);
        when(memberRepository.findByProviderUserId("kakao-1")).thenReturn(Optional.of(member));
        when(memberTokenService.issue(member)).thenReturn(new MemberTokenService.TokenPair("access", "refresh", 1L));
        when(memberProfileRepository.existsByMember_IdAndOnboardingCompletedAtIsNotNull(1L)).thenReturn(true);

        MemberLoginService.LoginResult result = service.login("code", "state");

        assertThat(result.newMember()).isFalse();
        assertThat(result.onboardingCompleted()).isTrue();
        assertThat(member.getNickname()).isEqualTo("기존닉네임");
        assertThat(member.getProfileImageUrl()).isEqualTo("https://old-image");
    }

    @Test
    @DisplayName("정지 이력이 있는 탈퇴 회원의 재가입은 거부한다")
    void 정지_이력_탈퇴_회원_재가입_거부() throws Exception {
        Member member = Member.create("kakao-1", "점심헌터", null);
        setId(member, 1L);
        setField(member, "status", MemberStatus.WITHDRAWN);
        setField(member, "suspendedAt", LocalDateTime.of(2026, 10, 1, 9, 0));
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "점심헌터", null));
        when(memberRepository.findByProviderUserId("kakao-1")).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.login("code", "state"))
                .isInstanceOfSatisfying(MemberException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED));
    }

    @Test
    @DisplayName("탈퇴 회원은 같은 카카오 번호로 재가입하고 unlink 실패 행을 지운다")
    void 탈퇴_회원_재가입() throws Exception {
        Member member = Member.create("kakao-1", "이전닉네임", null);
        setId(member, 1L);
        setField(member, "status", MemberStatus.WITHDRAWN);
        setField(member, "withdrawnAt", LocalDateTime.of(2026, 10, 1, 9, 0));
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "새닉네임", "https://image"));
        when(memberRepository.findByProviderUserId("kakao-1")).thenReturn(Optional.of(member));
        when(memberTokenService.issue(member)).thenReturn(new MemberTokenService.TokenPair("access", "refresh", 1L));
        when(memberProfileRepository.existsByMember_IdAndOnboardingCompletedAtIsNotNull(1L)).thenReturn(false);

        MemberLoginService.LoginResult result = service.login("code", "state");

        assertThat(result.newMember()).isTrue();
        assertThat(result.onboardingCompleted()).isFalse();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getNickname()).isEqualTo("새닉네임");
        verify(kakaoUnlinkFailureRepository).deleteByMember_Id(1L);
    }

    @Test
    @DisplayName("동시 가입 충돌이면 다시 조회한 기존 회원으로 로그인한다")
    void 동시_가입_충돌이면_기존_회원으로_로그인한다() throws Exception {
        Member member = Member.create("kakao-1", "기존닉네임", null);
        setId(member, 1L);
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "새닉네임", null));
        when(memberRepository.findByProviderUserId("kakao-1"))
                .thenReturn(Optional.empty(), Optional.of(member));
        when(memberRepository.saveAndFlush(any(Member.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate provider user id"));
        when(memberTokenService.issue(member)).thenReturn(new MemberTokenService.TokenPair("access", "refresh", 1L));
        when(memberProfileRepository.existsByMember_IdAndOnboardingCompletedAtIsNotNull(1L)).thenReturn(true);

        MemberLoginService.LoginResult result = service.login("code", "state");

        assertThat(result.member()).isSameAs(member);
        assertThat(result.newMember()).isFalse();
    }

    @Test
    @DisplayName("동시 가입 충돌 뒤에도 회원을 찾지 못하면 원래 충돌을 전파한다")
    void 동시_가입_충돌_후_회원을_찾지_못하면_충돌을_전파한다() {
        DataIntegrityViolationException duplicate = new DataIntegrityViolationException("duplicate provider user id");
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "점심헌터", null));
        when(memberRepository.findByProviderUserId("kakao-1")).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any(Member.class))).thenThrow(duplicate);

        assertThatThrownBy(() -> service.login("code", "state"))
                .isSameAs(duplicate);
    }

    private void setId(Member member, Long id) throws Exception {
        Field field = member.getClass().getSuperclass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(member, id);
    }

    private void setField(Member member, String fieldName, Object value) throws Exception {
        Field field = Member.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(member, value);
    }
}
