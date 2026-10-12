package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.member.client.KakaoUnlinkClient;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.KakaoUnlinkFailure;
import com.launchcatch.member.entity.KakaoUnlinkStopReason;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalServiceTest {

    @Mock KakaoIdTokenExchanger kakaoIdTokenExchanger;
    @Mock MemberRepository memberRepository;
    @Mock MemberProfileRepository memberProfileRepository;
    @Mock KakaoUnlinkFailureRepository kakaoUnlinkFailureRepository;
    @Mock MemberTokenService memberTokenService;
    @Mock KakaoUnlinkClient kakaoUnlinkClient;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;

    private MemberWithdrawalService memberWithdrawalService;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        memberWithdrawalService = new MemberWithdrawalService(
                kakaoIdTokenExchanger,
                memberRepository,
                memberProfileRepository,
                kakaoUnlinkFailureRepository,
                memberTokenService,
                kakaoUnlinkClient,
                new TransactionTemplate(transactionManager),
                clock);
    }

    @Test
    @DisplayName("재인증한 회원은 탈퇴한 뒤 카카오 연결을 해제한다")
    void 재인증한_회원은_탈퇴한_뒤_카카오_연결을_해제한다() throws Exception {
        Member member = member();
        MemberProfile profile = MemberProfile.create(member);
        givenReauthenticated(member);
        when(memberProfileRepository.findByMember_Id(1L)).thenReturn(Optional.of(profile));
        givenEmptyQueue();

        memberWithdrawalService.withdraw(1L, "code", "state");

        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getNickname()).isEqualTo("탈퇴한 회원");
        assertThat(member.getProfileImageUrl()).isNull();
        assertThat(member.getLastLoginAt()).isNull();
        assertThat(member.isNotificationOptIn()).isFalse();
        assertThat(member.isLocationOptIn()).isFalse();
        verify(memberProfileRepository).delete(profile);
        verify(memberTokenService).revoke(1L);
        verify(kakaoUnlinkClient).unlink("kakao-1");
        verify(kakaoUnlinkFailureRepository).deleteByMember_Id(1L);
    }

    /*
     * 유실 방지의 핵심이다. 해제 대상을 탈퇴 트랜잭션 안에서 남기므로, 커밋 직후 프로세스가
     * 죽어도 재시도 큐에 행이 있다. "실패한 뒤에 기록" 이면 그 구간이 전부 유실이 된다.
     */
    @Test
    @DisplayName("해제 대상은 카카오를 부르기 전에 큐에 올라간다")
    void 해제_대상은_카카오를_부르기_전에_큐에_올라간다() throws Exception {
        Member member = member();
        givenReauthenticated(member);
        AtomicReference<KakaoUnlinkFailure> queued = givenEmptyQueue();
        AtomicReference<KakaoUnlinkFailure> seenAtCallTime = new AtomicReference<>();
        doAnswer(invocation -> {
            seenAtCallTime.set(queued.get());
            throw new RuntimeException("network");
        }).when(kakaoUnlinkClient).unlink("kakao-1");

        memberWithdrawalService.withdraw(1L, "code", "state");

        assertThat(seenAtCallTime.get()).isNotNull();
        assertThat(seenAtCallTime.get().getProviderUserId()).isEqualTo("kakao-1");
        assertThat(seenAtCallTime.get().isResolved()).isFalse();
    }

    @Test
    @DisplayName("최초 unlink가 일시적으로 실패하면 큐 행을 그대로 둔다")
    void 최초_unlink가_일시적으로_실패하면_큐_행을_그대로_둔다() throws Exception {
        Member member = member();
        givenReauthenticated(member);
        AtomicReference<KakaoUnlinkFailure> queued = givenEmptyQueue();
        doThrow(new RuntimeException("network")).when(kakaoUnlinkClient).unlink("kakao-1");

        memberWithdrawalService.withdraw(1L, "code", "state");

        assertThat(queued.get().getAttemptCount()).isZero();
        assertThat(queued.get().isResolved()).isFalse();
        verify(kakaoUnlinkFailureRepository, never()).deleteByMember_Id(1L);
    }

    @Test
    @DisplayName("최초 unlink가 400으로 거절되면 영구 거부로 닫는다")
    void 최초_unlink가_400으로_거절되면_영구_거부로_닫는다() throws Exception {
        Member member = member();
        givenReauthenticated(member);
        AtomicReference<KakaoUnlinkFailure> queued = givenEmptyQueue();
        doThrow(kakaoResponded(HttpStatus.BAD_REQUEST)).when(kakaoUnlinkClient).unlink("kakao-1");

        memberWithdrawalService.withdraw(1L, "code", "state");

        assertThat(queued.get().isResolved()).isTrue();
        assertThat(queued.get().getStopReason()).isEqualTo(KakaoUnlinkStopReason.REJECTED);
        assertThat(queued.get().getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("최초 unlink가 429로 막히면 닫지 않고 재시도에 맡긴다")
    void 최초_unlink가_429로_막히면_닫지_않고_재시도에_맡긴다() throws Exception {
        Member member = member();
        givenReauthenticated(member);
        AtomicReference<KakaoUnlinkFailure> queued = givenEmptyQueue();
        doThrow(kakaoResponded(HttpStatus.TOO_MANY_REQUESTS)).when(kakaoUnlinkClient).unlink("kakao-1");

        memberWithdrawalService.withdraw(1L, "code", "state");

        assertThat(queued.get().isResolved()).isFalse();
        assertThat(queued.get().getStopReason()).isNull();
    }

    /*
     * 재가입이 대기 행을 지우지 못한 경우다. UNIQUE(member_id) 때문에 새 행을 넣을 수 없고,
     * 닫힌 행을 그대로 두면 이번 탈퇴의 해제가 한 번도 시도되지 않는다.
     */
    @Test
    @DisplayName("남아 있던 닫힌 행은 다시 열어서 쓴다")
    void 남아_있던_닫힌_행은_다시_열어서_쓴다() throws Exception {
        Member member = member();
        KakaoUnlinkFailure stale = KakaoUnlinkFailure.create(member);
        stale.rejectPermanently();
        givenReauthenticated(member);
        when(kakaoUnlinkFailureRepository.findByMember_Id(1L)).thenReturn(Optional.of(stale));
        doThrow(new RuntimeException("network")).when(kakaoUnlinkClient).unlink("kakao-1");

        memberWithdrawalService.withdraw(1L, "code", "state");

        assertThat(stale.isResolved()).isFalse();
        assertThat(stale.getStopReason()).isNull();
        verify(kakaoUnlinkFailureRepository, never()).save(any(KakaoUnlinkFailure.class));
    }

    @Test
    @DisplayName("재인증한 카카오 계정이 다르면 탈퇴를 거부한다")
    void 재인증한_카카오_계정이_다르면_탈퇴를_거부한다() throws Exception {
        Member member = member();
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(identity("other"));
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(1L, "code", "state"))
                .isInstanceOf(AuthException.class);

        verify(kakaoUnlinkFailureRepository, never()).save(any(KakaoUnlinkFailure.class));
    }

    @Test
    @DisplayName("이미 탈퇴한 회원은 다시 탈퇴할 수 없다")
    void 이미_탈퇴한_회원은_다시_탈퇴할_수_없다() throws Exception {
        Member member = member();
        setField(member, "status", MemberStatus.WITHDRAWN);
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(identity("kakao-1"));
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(1L, "code", "state"))
                .isInstanceOfSatisfying(MemberException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.ALREADY_WITHDRAWN));
    }

    @Test
    @DisplayName("웹훅은 로컬 탈퇴만 처리하고 unlink를 다시 호출하지 않는다")
    void 웹훅은_로컬_탈퇴만_처리하고_unlink를_다시_호출하지_않는다() throws Exception {
        Member member = member();
        when(memberRepository.findByProviderUserIdForUpdate("kakao-1")).thenReturn(Optional.of(member));

        memberWithdrawalService.withdrawByKakaoWebhook("kakao-1");

        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        verify(memberTokenService).revoke(1L);
        verify(kakaoUnlinkFailureRepository).deleteByMember_Id(1L);
        verify(kakaoUnlinkClient, never()).unlink(any());
        verify(kakaoUnlinkFailureRepository, never()).save(any(KakaoUnlinkFailure.class));
    }

    @Test
    @DisplayName("이미 탈퇴한 회원의 웹훅은 남은 대기 행만 정리한다")
    void 이미_탈퇴한_회원의_웹훅은_남은_대기_행만_정리한다() throws Exception {
        Member member = member();
        setField(member, "status", MemberStatus.WITHDRAWN);
        when(memberRepository.findByProviderUserIdForUpdate("kakao-1")).thenReturn(Optional.of(member));

        memberWithdrawalService.withdrawByKakaoWebhook("kakao-1");

        verify(kakaoUnlinkFailureRepository).deleteByMember_Id(1L);
        verify(memberTokenService, never()).revoke(any());
    }

    private void givenReauthenticated(Member member) {
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(identity("kakao-1"));
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));
    }

    /*
     * 큐를 흉내 낸다. 저장한 행을 그대로 돌려줘야 탈퇴 트랜잭션이 올린 행을 뒤 단계가 다시
     * 찾는 경로까지 검증할 수 있다.
     */
    private AtomicReference<KakaoUnlinkFailure> givenEmptyQueue() {
        AtomicReference<KakaoUnlinkFailure> queued = new AtomicReference<>();
        when(kakaoUnlinkFailureRepository.findByMember_Id(1L))
                .thenAnswer(invocation -> Optional.ofNullable(queued.get()));
        when(kakaoUnlinkFailureRepository.save(any(KakaoUnlinkFailure.class)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(0));
                    return invocation.getArgument(0);
                });
        return queued;
    }

    private WebClientResponseException kakaoResponded(HttpStatus status) {
        return WebClientResponseException.create(
                status.value(), status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private KakaoIdentity identity(String providerUserId) {
        return new KakaoIdentity(providerUserId, "닉네임", null);
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
