package com.launchcatch.member.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.global.logging.TransactionLogging;
import com.launchcatch.member.client.KakaoUnlinkClient;
import com.launchcatch.member.client.KakaoUnlinkFailureClassifier;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.KakaoUnlinkFailure;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberWithdrawalHistory;
import io.micrometer.core.instrument.Metrics;
import com.launchcatch.member.exception.MemberErrorCode;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberWithdrawalHistoryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
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
    private final MemberWithdrawalHistoryRepository withdrawalHistoryRepository;
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
        UnlinkTarget target;
        try {
            KakaoIdentity identity = kakaoIdTokenExchanger.exchange(authorizationCode, state);
            target = transactionTemplate.execute(status -> withdrawLocally(memberId, identity.providerUserId()));
            if (target == null) {
                throw new IllegalStateException("탈퇴 처리 결과가 없습니다.");
            }
        } catch (BusinessException e) {
            if (e.getErrorCode().getHttpStatus().is5xxServerError()) {
                log.error("event=MEMBER_WITHDRAWAL_FAILED memberId={} errorCode={} details={}", memberId, e.getErrorCode().getCode(), FailureLogDetails.from(e));
            } else {
                log.warn("event=MEMBER_WITHDRAWAL_FAILED memberId={} errorCode={}", memberId, e.getErrorCode().getCode());
            }
            throw e;
        } catch (RuntimeException e) {
            log.error("event=MEMBER_WITHDRAWAL_FAILED memberId={} reason=SYSTEM_ERROR exceptionType={}", memberId, e.getClass().getSimpleName());
            throw e;
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
     * 카카오 연결 해제 통보로 최초 가입 계정을 탈퇴시킨다.
     * 기존 작업을 삭제하면 실행 중 외부 호출이 있는데도 재가입이 허용되므로 큐는 바꾸지 않는다.
     */
    public void withdrawByKakaoWebhook(String providerUserId) {
        transactionTemplate.executeWithoutResult(status ->
                memberRepository.findByProviderUserIdForUpdate(providerUserId).ifPresent(member -> {
                    /*
                     * 현재 웹훅 계약에는 가입 회차와 발생 시각이 없다.
                     * 재가입 계정에서는 과거 통보와 구분할 수 없어 계정을 유지하고 운영 확인 대상으로 센다.
                     */
                    if (withdrawalHistoryRepository.existsByProviderUserId(providerUserId)) {
                        Metrics.counter("member.unlink.webhook.ambiguous").increment();
                        log.error("event=KAKAO_UNLINK_WEBHOOK_AMBIGUOUS memberId={} action=REQUIRES_VERIFICATION", member.getId());
                        return;
                    }
                    if (member.getStatus() != MemberStatus.WITHDRAWN) {
                        withdrawMember(member, "KAKAO_WEBHOOK");
                    }
                    Long memberId = member.getId();
                    TransactionLogging.afterCommit(() -> log.info("event=KAKAO_UNLINK_WEBHOOK_SUCCESS memberId={}", memberId));
                }));
    }

    private UnlinkTarget withdrawLocally(Long memberId, String authenticatedProviderUserId) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new NoSuchElementException("인증된 회원 정보를 찾을 수 없습니다."));
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new MemberException(MemberErrorCode.ALREADY_WITHDRAWN);
        }
        if (!member.getProviderUserId().equals(authenticatedProviderUserId)) {
            throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED);
        }
        enqueueUnlink(member);
        String providerUserId = member.getProviderUserId();
        withdrawMember(member, "MEMBER_REQUEST");
        return new UnlinkTarget(member.getId(), providerUserId);
    }

    private void withdrawMember(Member member, String source) {
        var profile = memberProfileRepository.findByMember_Id(member.getId());
        LocalDateTime now = LocalDateTime.now(clock);
        withdrawalHistoryRepository.save(MemberWithdrawalHistory.create(member, now));
        member.withdraw(now);
        profile.ifPresent(memberProfileRepository::delete);
        memberTokenService.revoke(member.getId());
        Long memberId = member.getId();
        TransactionLogging.afterCommit(() -> log.info("event=MEMBER_WITHDRAWAL_SUCCESS memberId={} source={}", memberId, source));
    }

    /*
     * 회원의 카카오 번호를 비우기 전에 해제 대상을 저장하고 최초 호출을 선점한다.
     * 이력과 함께 커밋되므로 배치가 같은 작업을 동시에 호출하지 않는다.
     */
    private void enqueueUnlink(Member member) {
        KakaoUnlinkFailure failure = kakaoUnlinkFailureRepository.findByMember_Id(member.getId())
                .orElseGet(() -> kakaoUnlinkFailureRepository.save(KakaoUnlinkFailure.create(member)));
        if (!failure.claim()) {
            throw new MemberException(MemberErrorCode.UNLINK_PENDING);
        }
    }

    /*
     * 원 시도가 실패했다. 행은 탈퇴 트랜잭션에서 이미 만들어져 있으므로 여기서 만들지 않는다.
     * 선점을 해제하고 카카오가 영구적으로 거부한 경우에만 닫는다.
     * 최초 호출의 실패는 재시도 횟수에 포함하지 않는다.
     */
    private void markInitialUnlinkFailure(UnlinkTarget target, RuntimeException failure) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    kakaoUnlinkFailureRepository.findByMember_Id(target.memberId())
                            .ifPresent(row -> {
                                row.release();
                                if (KakaoUnlinkFailureClassifier.isTerminal(failure)) {
                                    row.rejectPermanently();
                                }
                            }));
        } catch (RuntimeException persistenceFailure) {
            log.error("event=KAKAO_UNLINK_REJECTION_NOT_RECORDED memberId={}",
                    target.memberId(), persistenceFailure);
        }
        if (!KakaoUnlinkFailureClassifier.isTerminal(failure)) {
            log.warn("event=KAKAO_UNLINK_FAILED_QUEUED memberId={} exceptionType={} details={}",
                    target.memberId(), KakaoUnlinkFailureClassifier.causeType(failure), FailureLogDetails.from(failure));
            return;
        }
        log.error("event=KAKAO_UNLINK_REJECTED memberId={} exceptionType={}",
                target.memberId(),
                KakaoUnlinkFailureClassifier.causeType(failure), failure);
    }

    private record UnlinkTarget(Long memberId, String providerUserId) {
    }
}
