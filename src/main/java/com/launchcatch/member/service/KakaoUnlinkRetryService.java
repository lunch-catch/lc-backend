package com.launchcatch.member.service;

import com.launchcatch.global.logging.PiiMasker;
import com.launchcatch.member.client.KakaoUnlinkClient;
import com.launchcatch.member.client.KakaoUnlinkFailureClassifier;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.KakaoUnlinkFailure;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 아직 해제되지 않은 카카오 unlink 를 다시 호출한다.
 *
 * 전체를 한 트랜잭션으로 묶지 않는다. 가운데에 카카오 호출이 있고, 트랜잭션 안에서 외부를
 * 부르면 그 대기 동안 커넥션과 락을 쥔다(DI-4-02, 예외 없음). 그래서 짧은 트랜잭션 셋으로
 * 나눈다. 후보 읽기, 호출 직전 재확인, 결과 반영이다. 각 단계가 자기 클래스의 private 메서드라
 * @Transactional 은 프록시를 거치지 못하고 조용히 무시된다. TransactionTemplate 을 쓰는 이유다.
 *
 * 두 배치 서버가 겹치는 것은 이 클래스가 막지 않는다. 스케줄러가 batch_execution_log 로
 * 작업을 점유하므로 여기까지 온 서버는 이 작업을 혼자 들고 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoUnlinkRetryService {

    public static final int MAX_RETRY_ATTEMPTS = 5;

    /** 한 번에 처리할 행 수다. 건마다 외부 호출이 붙어 사이클 길이가 이 값에 비례한다. */
    private static final int BATCH_SIZE = 100;

    private final KakaoUnlinkFailureRepository failureRepository;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final TransactionTemplate transactionTemplate;

    public void retryPending() {
        List<PendingUnlink> pending = transactionTemplate.execute(status -> loadPending());
        if (pending == null) {
            return;
        }
        pending.forEach(this::retryOne);
    }

    /*
     * 엔티티를 그대로 들고 나가지 않는다. member 가 LAZY 라 트랜잭션이 닫힌 뒤에 만지면
     * LazyInitializationException 이다. 호출에 필요한 값만 떼어 낸다.
     */
    private List<PendingUnlink> loadPending() {
        return failureRepository.findPendingOldestFirst(PageRequest.of(0, BATCH_SIZE)).stream()
                .map(failure -> new PendingUnlink(
                        failure.getId(), failure.getMember().getId(), failure.getProviderUserId()))
                .toList();
    }

    /*
     * 외부 호출 실패와 결과 반영 실패를 다른 경로로 둔다.
     *
     * 한 try 로 묶으면 unlink 는 성공했는데 행 삭제가 실패한 경우가 호출 실패로 집계되어
     * attempt_count 가 오르고, 성공한 해제가 실패로 기록된다. 삭제가 실패해도 카카오 쪽은
     * 이미 끊겼고 남은 행은 다음 사이클이 다시 호출할 뿐이다(unlink 는 멱등).
     */
    private void retryOne(PendingUnlink pending) {
        Boolean eligible = transactionTemplate.execute(status -> stillNeedsUnlink(pending));
        if (!Boolean.TRUE.equals(eligible)) {
            return;
        }
        try {
            kakaoUnlinkClient.unlink(pending.providerUserId());
        } catch (CallNotPermittedException circuitOpen) {
            /*
             * 서킷이 열려 카카오에 묻지도 못했다. 횟수를 올리면 "다섯 번 시도했는데도 실패"
             * 라는 한도의 뜻이 깨진다. 카카오 장애가 길어지면 한 번도 제대로 묻지 못한 채
             * 포기 처리될 수 있다. 그대로 두고 다음 사이클에 다시 시도한다.
             */
            log.info("event=KAKAO_UNLINK_RETRY_SKIPPED_CIRCUIT_OPEN failureId={}", pending.failureId());
            return;
        } catch (RuntimeException callFailure) {
            transactionTemplate.executeWithoutResult(status -> markFailed(pending, callFailure));
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> markSucceeded(pending));
        } catch (RuntimeException cleanupFailure) {
            log.error("event=KAKAO_UNLINK_RETRY_CLEANUP_FAILED failureId={} memberId={}",
                    pending.failureId(), pending.memberId(), cleanupFailure);
        }
    }

    /*
     * 호출 직전에 다시 본다. 후보를 읽은 시점과 호출 시점 사이에 재가입이 일어날 수 있다.
     * 재가입은 대기 행을 지우지만, 그 사이에 이 서버가 행을 들고 있었다면 여기서 걸러진다.
     */
    private boolean stillNeedsUnlink(PendingUnlink pending) {
        return failureRepository.findById(pending.failureId())
                .map(failure -> {
                    if (failure.isResolved()) {
                        return false;
                    }
                    if (failure.getMember().getStatus() == MemberStatus.WITHDRAWN) {
                        return true;
                    }
                    failureRepository.delete(failure);
                    log.info("event=KAKAO_UNLINK_RETRY_DISCARDED_REACTIVATED failureId={} memberId={}",
                            failure.getId(), failure.getMember().getId());
                    return false;
                })
                .orElse(false);
    }

    /*
     * 해제에 성공했으니 행을 지운다.
     *
     * 재확인과 호출 사이의 창은 외부 호출을 트랜잭션 밖에 두는 한 없앨 수 없다. 닫는 대신
     * 지나간 뒤에 탐지한다. 행이 사라졌거나 회원이 더 이상 WITHDRAWN 이 아니면 호출 중에
     * 재가입이 끼어든 것이고, 방금 맺은 카카오 연결을 우리가 끊었다는 뜻이다. 사용자에게
     * 재연동을 안내해야 하므로 경보로 남긴다.
     */
    private void markSucceeded(PendingUnlink pending) {
        Optional<KakaoUnlinkFailure> row = failureRepository.findById(pending.failureId());
        if (row.isEmpty()) {
            log.error("event=KAKAO_UNLINK_RACED_REACTIVATION memberId={} cause=QUEUE_ROW_DELETED action=REQUIRES_RELINK",
                    pending.memberId());
            return;
        }
        KakaoUnlinkFailure failure = row.get();
        if (failure.getMember().getStatus() != MemberStatus.WITHDRAWN) {
            log.error("event=KAKAO_UNLINK_RACED_REACTIVATION memberId={} cause=MEMBER_REACTIVATED action=REQUIRES_RELINK",
                    pending.memberId());
        }
        failureRepository.delete(failure);
    }

    private void markFailed(PendingUnlink pending, RuntimeException cause) {
        failureRepository.findById(pending.failureId()).ifPresent(failure -> {
            String causeType = KakaoUnlinkFailureClassifier.causeType(cause);
            String maskedProviderId = PiiMasker.maskProviderId(failure.getProviderUserId());
            if (KakaoUnlinkFailureClassifier.isTerminal(cause)) {
                failure.rejectPermanently();
                log.error("event=KAKAO_UNLINK_RETRY_REJECTED failureId={} memberId={} providerUserId={} causeType={}",
                        failure.getId(), pending.memberId(), maskedProviderId, causeType, cause);
                return;
            }
            int attempts = failure.recordRetryFailure(MAX_RETRY_ATTEMPTS);
            if (failure.isResolved()) {
                log.error("event=KAKAO_UNLINK_RETRY_EXHAUSTED failureId={} memberId={} providerUserId={} attempts={} causeType={}",
                        failure.getId(), pending.memberId(), maskedProviderId, attempts, causeType, cause);
            } else {
                log.warn("event=KAKAO_UNLINK_RETRY_FAILED failureId={} memberId={} providerUserId={} attempts={} causeType={}",
                        failure.getId(), pending.memberId(), maskedProviderId, attempts, causeType, cause);
            }
        });
    }

    private record PendingUnlink(Long failureId, Long memberId, String providerUserId) {
    }
}
