package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberWithdrawalHistory;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberWithdrawalHistoryRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
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

    @Mock
    private MemberWithdrawalHistoryRepository withdrawalHistoryRepository;

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
                withdrawalHistoryRepository,
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
        when(memberRepository.findByProviderUserIdForUpdate("kakao-1")).thenReturn(Optional.empty());
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
        when(memberRepository.findByProviderUserIdForUpdate("kakao-1")).thenReturn(Optional.of(member));
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
        when(withdrawalHistoryRepository.existsByProviderUserIdAndSuspendedAtIsNotNull("kakao-1"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.login("code", "state"))
                .isInstanceOfSatisfying(MemberException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.SUSPENDED_REJOIN_NOT_ALLOWED));
    }

    @Test
    @DisplayName("탈퇴 다음 날에는 같은 카카오 번호로 새 회원을 만든다")
    void 탈퇴_회원_재가입() throws Exception {
        Member member = Member.create("kakao-1", "이전닉네임", null);
        setId(member, 1L);
        var history = MemberWithdrawalHistory.create(
                member, LocalDateTime.of(2026, 10, 1, 9, 0));
        member.withdraw(history.getWithdrawnAt());
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "새닉네임", "https://image"));
        when(withdrawalHistoryRepository.findFirstByProviderUserIdOrderByWithdrawnAtDescIdDesc("kakao-1"))
                .thenReturn(Optional.of(history));
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> {
            Member fresh = invocation.getArgument(0);
            setId(fresh, 2L);
            return fresh;
        });
        when(memberTokenService.issue(any(Member.class)))
                .thenReturn(new MemberTokenService.TokenPair("access", "refresh", 2L));

        MemberLoginService.LoginResult result = service.login("code", "state");

        assertThat(result.newMember()).isTrue();
        assertThat(result.onboardingCompleted()).isFalse();
        assertThat(result.member().getId()).isEqualTo(2L);
        assertThat(result.member().getNickname()).isEqualTo("새닉네임");
        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getWithdrawnAt()).isEqualTo(history.getWithdrawnAt());
        assertThat(member.getProviderUserId()).isNull();
    }

    @Test
    @DisplayName("동시 가입 충돌이면 다시 조회한 기존 회원으로 로그인한다")
    void 동시_가입_충돌이면_기존_회원으로_로그인한다() throws Exception {
        Member member = Member.create("kakao-1", "기존닉네임", null);
        setId(member, 1L);
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "새닉네임", null));
        when(memberRepository.findByProviderUserIdForUpdate("kakao-1"))
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
        when(memberRepository.findByProviderUserIdForUpdate("kakao-1")).thenReturn(Optional.empty());
        when(memberRepository.saveAndFlush(any(Member.class))).thenThrow(duplicate);

        assertThatThrownBy(() -> service.login("code", "state"))
                .isSameAs(duplicate);
    }

    @Test
    void 탈퇴한_날의_재가입을_거부한다() throws Exception {
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "새닉네임", null));
        Member previous = Member.create("kakao-1", "이전닉네임", null);
        setId(previous, 1L);
        var history = MemberWithdrawalHistory.create(
                previous, LocalDateTime.of(2026, 10, 6, 0, 0));
        when(withdrawalHistoryRepository.findFirstByProviderUserIdOrderByWithdrawnAtDescIdDesc("kakao-1"))
                .thenReturn(Optional.of(history));

        assertThatThrownBy(() -> service.login("code", "state"))
                .isInstanceOfSatisfying(MemberException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("MEMBER-004"));
    }

    @Test
    void 해제_작업이_남으면_다음_날에도_재가입을_보류한다() {
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "새닉네임", null));
        when(kakaoUnlinkFailureRepository.existsByProviderUserId("kakao-1")).thenReturn(true);

        assertThatThrownBy(() -> service.login("code", "state"))
                .isInstanceOfSatisfying(MemberException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.UNLINK_PENDING));
        org.mockito.Mockito.verifyNoInteractions(memberTokenService);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-06T14:59:59.999999999Z,false",
            "2026-10-06T15:00:00Z,true"
    })
    void 한국_자정부터_재가입을_허용한다(String instant, boolean allowed) throws Exception {
        service = new MemberLoginService(kakaoIdTokenExchanger, memberRepository, memberProfileRepository,
                kakaoUnlinkFailureRepository, withdrawalHistoryRepository, memberTokenService, transactionTemplate,
                Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Seoul")));
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "새닉네임", null));
        Member previous = Member.create("kakao-1", "이전닉네임", null);
        setId(previous, 1L);
        when(withdrawalHistoryRepository.findFirstByProviderUserIdOrderByWithdrawnAtDescIdDesc("kakao-1"))
                .thenReturn(Optional.of(MemberWithdrawalHistory.create(
                        previous, LocalDateTime.of(2026, 10, 6, 23, 58))));
        if (!allowed) {
            assertThatThrownBy(() -> service.login("code", "state"))
                    .isInstanceOfSatisfying(MemberException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.SAME_DAY_REJOIN_NOT_ALLOWED));
            return;
        }
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> {
            Member fresh = invocation.getArgument(0);
            setId(fresh, 2L);
            return fresh;
        });
        when(memberTokenService.issue(any(Member.class)))
                .thenReturn(new MemberTokenService.TokenPair("access", "refresh", 2L));

        assertThat(service.login("code", "state").member().getId()).isEqualTo(2L);
    }

    @Test
    void 일시적인_잠금_교착은_새_트랜잭션으로_다시_판정한다() throws Exception {
        when(kakaoIdTokenExchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity("kakao-1", "닉네임", null));
        Member member = Member.create("kakao-1", "닉네임", null);
        setId(member, 1L);
        when(memberRepository.findByProviderUserIdForUpdate("kakao-1"))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(Optional.of(member));
        when(memberTokenService.issue(member))
                .thenReturn(new MemberTokenService.TokenPair("access", "refresh", 1L));

        assertThat(service.login("code", "state").member()).isSameAs(member);
        verify(transactionTemplate, org.mockito.Mockito.times(2)).execute(any());
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
