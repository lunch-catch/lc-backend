package com.launchcatch.member.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.global.logging.PiiMasker;
import com.launchcatch.member.client.KakaoUnlinkClient;
import com.launchcatch.member.client.KakaoUnlinkFailureClassifier;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.KakaoUnlinkFailure;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {
    private final KakaoIdTokenExchanger kakaoIdTokenExchanger;
    private final MemberRepository memberRepository;
    private final MemberProfileRepository memberProfileRepository;
    private final KakaoUnlinkFailureRepository kakaoUnlinkFailureRepository;
    private final MemberTokenService memberTokenService;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /*
     * 탈퇴하고 카카오 연결을 해제한다.
     *
     * 해제 대상을 탈퇴 트랜잭션 안에서 먼저 남긴다(아웃박스). 카카오 호출은 그 커밋 뒤에
     * 트랜잭션 밖에서 한다. 트랜잭션 안에서 외부를 부르면 대기 동안 커넥션과 락을 쥔다
     * (DI-4-02).
     *
     * 순서가 핵심이다. "호출이 실패하면 그때 행을 남긴다" 로 두면 탈퇴 커밋과 그 기록 사이의
     * 모든 중단이 유실이 된다. 프로세스가 죽거나 기록 자체가 실패하면 회원은 WITHDRAWN 인데
     * 재시도 큐에는 아무것도 없고, 카카오 연결은 영원히 남는다. 응답은 204 라 아무도 모른다.
     * 먼저 남겨 두면 그 사이에 무엇이 멈춰도 다음 배치가 집어 올린다.
     *
     * 해제에 성공하면 그 행을 지운다. 지우지 못해도 탈퇴는 그대로다. 남은 행은 다음 배치가
     * 다시 호출할 뿐이고 카카오 unlink 는 멱등이다.
     */
    public void withdraw(Long memberId, String authorizationCode, String state) {
        KakaoIdentity identity = kakaoIdTokenExchanger.exchange(authorizationCode, state);
        UnlinkTarget target = transactionTemplate.execute(status -> withdrawLocally(memberId, identity.providerUserId()));
        if (target == null) {
            throw new IllegalStateException("withdrawal transaction returned no target");
        }
        try {
            kakaoUnlinkClient.unlink(target.providerUserId());
        } catch (RuntimeException callFailure) {
            markInitialUnlinkFailure(target, callFailure);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status ->
                    kakaoUnlinkFailureRepository.deleteByMember_Id(target.memberId()));
        } catch (RuntimeException cleanupFailure) {
            log.error("event=KAKAO_UNLINK_SUCCESS_CLEANUP_FAILED memberId={}",
                    target.memberId(), cleanupFailure);
        }
    }

    /*
     * 카카오가 연결 해제를 통보했다. 카카오 쪽은 이미 끊겼으니 우리가 호출할 것이 없다.
     * 대기 중인 해제 요청도 함께 지운다. 남겨 두면 배치가 끊을 것이 없는 대상을 계속 부른다.
     */
    public void withdrawByKakaoWebhook(String providerUserId) {
        transactionTemplate.executeWithoutResult(status ->
                memberRepository.findByProviderUserIdForUpdate(providerUserId).ifPresent(member -> {
                    if (member.getStatus() != MemberStatus.WITHDRAWN) {
                        withdrawMember(member);
                    }
                    kakaoUnlinkFailureRepository.deleteByMember_Id(member.getId());
                }));
    }

    private UnlinkTarget withdrawLocally(Long memberId, String authenticatedProviderUserId) {
        Member member = memberRepository.findByIdForUpdate(memberId).orElseThrow();
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
        }
        if (!member.getProviderUserId().equals(authenticatedProviderUserId)) {
            throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED);
        }
        withdrawMember(member);
        enqueueUnlink(member);
        return new UnlinkTarget(member.getId(), member.getProviderUserId());
    }

    private void withdrawMember(Member member) {
        memberProfileRepository.findByMember_Id(member.getId()).ifPresent(memberProfileRepository::delete);
        member.withdraw(LocalDateTime.now(clock));
        memberTokenService.revoke(member.getId());
    }

    /*
     * 해제 대상을 큐에 올린다. 탈퇴와 같은 트랜잭션이라 둘이 함께 커밋되거나 함께 되돌아간다.
     * 행이 이미 있으면 처음 상태로 되돌린다. UNIQUE(member_id) 때문에 새로 넣을 수 없고,
     * 닫힌 행을 그대로 두면 이번 탈퇴의 해제가 시도되지 않는다.
     */
    private void enqueueUnlink(Member member) {
        kakaoUnlinkFailureRepository.findByMember_Id(member.getId()).ifPresentOrElse(
                KakaoUnlinkFailure::reopen,
                () -> kakaoUnlinkFailureRepository.save(KakaoUnlinkFailure.create(member)));
    }

    /*
     * 원 시도가 실패했다. 행은 탈퇴 트랜잭션에서 이미 만들어져 있으므로 여기서 만들지 않는다.
     * 카카오가 영구적으로 거부한 경우에만 닫고, 그 밖에는 손대지 않는다. 재시도 횟수는
     * 재시도가 실패했을 때만 오른다. 이 실패 때문에 행이 남아 있는 것이므로 세면 한도가
     * 실제 재시도 횟수보다 한 번 적어진다.
     */
    private void markInitialUnlinkFailure(UnlinkTarget target, RuntimeException failure) {
        if (!KakaoUnlinkFailureClassifier.isTerminal(failure)) {
            log.warn("event=KAKAO_UNLINK_FAILED_QUEUED memberId={} causeType={}",
                    target.memberId(), KakaoUnlinkFailureClassifier.causeType(failure));
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status ->
                    kakaoUnlinkFailureRepository.findByMember_Id(target.memberId())
                            .ifPresent(KakaoUnlinkFailure::rejectPermanently));
        } catch (RuntimeException persistenceFailure) {
            log.error("event=KAKAO_UNLINK_REJECTION_NOT_RECORDED memberId={}",
                    target.memberId(), persistenceFailure);
        }
        log.error("event=KAKAO_UNLINK_REJECTED memberId={} providerUserId={} causeType={}",
                target.memberId(), PiiMasker.maskProviderId(target.providerUserId()),
                KakaoUnlinkFailureClassifier.causeType(failure), failure);
    }

    private record UnlinkTarget(Long memberId, String providerUserId) {
    }
}
