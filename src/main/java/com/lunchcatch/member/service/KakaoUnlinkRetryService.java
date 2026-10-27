package com.lunchcatch.member.service;

import com.lunchcatch.global.logging.TransactionLogging;
import com.lunchcatch.global.logging.FailureLogDetails;
import com.lunchcatch.member.client.KakaoUnlinkClient;
import com.lunchcatch.member.client.KakaoUnlinkFailureClassifier;
import com.lunchcatch.member.contract.MemberStatus;
import com.lunchcatch.member.entity.KakaoUnlinkFailure;
import com.lunchcatch.member.repository.KakaoUnlinkFailureRepository;
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
        // execute 는 콜백이 null 을 돌려줄 수 있어 @Nullable 이다. 빈 목록으로 바꿔 분기 없이 돈다
        Optional.ofNullable(transactionTemplate.execute(status -> loadPending()))
                .orElse(List.of())
                .forEach(this::retryOne);
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
     * 이미 끊겼고 선점이 남은 행은 장애 처리 설계의 운영 복구 절차를 따른다.
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
            log.warn("event=KAKAO_UNLINK_RETRY_SKIPPED failureId={} memberId={} reason=CIRCUIT_OPEN", pending.failureId(), pending.memberId());
            recordFailure(pending, circuitOpen);
            return;
        } catch (RuntimeException callFailure) {
            recordFailure(pending, callFailure);
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
     * 후보를 읽은 뒤 최초 호출이나 다른 실행자가 선점했을 수 있어 행을 잠가 다시 확인한다.
     * 선점 상태는 커밋 뒤 외부 호출이 끝날 때까지 유지한다.
     */
    private boolean stillNeedsUnlink(PendingUnlink pending) {
        return failureRepository.findByIdForUpdate(pending.failureId())
                .map(failure -> {
                    if (failure.isResolved()) {
                        return false;
                    }
                    if (failure.getMember().getStatus() == MemberStatus.WITHDRAWN) {
                        return failure.claim();
                    }
                    failureRepository.delete(failure);
                    TransactionLogging.afterCommit(() -> log.info("event=KAKAO_UNLINK_RETRY_DISCARDED_REACTIVATED failureId={} memberId={}",
                            pending.failureId(), pending.memberId()));
                    return false;
                })
                .orElse(false);
    }

    /*
     * 해제에 성공했으니 행을 지운다.
     *
     * 정상 경로는 작업 행이 있는 동안 재가입을 막고 옛 회원을 재활성화하지 않는다.
     * 운영 중 행이 사라지거나 옛 계정이 활성화된 경우는 이 규칙을 벗어난 상태이므로 경보로 남긴다.
     */
    private void markSucceeded(PendingUnlink pending) {
        Optional<KakaoUnlinkFailure> row = failureRepository.findById(pending.failureId());
        if (row.isEmpty()) {
            log.error("event=KAKAO_UNLINK_RACED_REACTIVATION memberId={} reason=QUEUE_ROW_DELETED action=REQUIRES_RELINK",
                    pending.memberId());
            return;
        }
        KakaoUnlinkFailure failure = row.get();
        if (failure.getMember().getStatus() != MemberStatus.WITHDRAWN) {
            log.error("event=KAKAO_UNLINK_RACED_REACTIVATION memberId={} reason=MEMBER_REACTIVATED action=REQUIRES_RELINK",
                    pending.memberId());
        }
        failureRepository.delete(failure);
        TransactionLogging.afterCommit(() -> log.info("event=KAKAO_UNLINK_RETRY_SUCCESS failureId={} memberId={}",
                pending.failureId(), pending.memberId()));
    }

    /*
     * 실패를 기록하는 트랜잭션이 DB 오류로 던져도 이 행에서 멈춘다. 밖으로 나가면 forEach 가 끊겨
     * 남은 행이 그날 재시도되지 않고 스케줄러가 작업 전체를 FAILED 로 닫는다. 성공 경로의 정리 실패와 같은 처리다.
     * 기록이 실패해 선점이 남으면 자동 재호출하지 않고 운영 확인 뒤 선점을 해제한다.
     */
    private void recordFailure(PendingUnlink pending, RuntimeException callFailure) {
        try {
            transactionTemplate.executeWithoutResult(status -> markFailed(pending, callFailure));
        } catch (RuntimeException recordFailure) {
            log.error("event=KAKAO_UNLINK_RETRY_MARK_FAILED failureId={} memberId={} callExceptionType={}",
                    pending.failureId(), pending.memberId(),
                    KakaoUnlinkFailureClassifier.causeType(callFailure), recordFailure);
        }
    }

    private void markFailed(PendingUnlink pending, RuntimeException cause) {
        failureRepository.findById(pending.failureId()).ifPresentOrElse(failure -> {
            failure.release();
            if (cause instanceof CallNotPermittedException) {
                return;
            }
            String causeType = KakaoUnlinkFailureClassifier.causeType(cause);
            Long failureId = failure.getId();
            if (KakaoUnlinkFailureClassifier.isTerminal(cause)) {
                failure.rejectPermanently();
                TransactionLogging.afterCommit(() -> log.error("event=KAKAO_UNLINK_RETRY_REJECTED failureId={} memberId={} exceptionType={}",
                        failureId, pending.memberId(), causeType, cause));
                return;
            }
            int attempts = failure.recordRetryFailure(MAX_RETRY_ATTEMPTS);
            if (failure.isResolved()) {
                TransactionLogging.afterCommit(() -> log.error("event=KAKAO_UNLINK_RETRY_EXHAUSTED failureId={} memberId={} attempts={} exceptionType={}",
                        failureId, pending.memberId(), attempts, causeType, cause));
            } else {
                TransactionLogging.afterCommit(() -> log.warn("event=KAKAO_UNLINK_RETRY_FAILED failureId={} memberId={} attempts={} exceptionType={}",
                        failureId, pending.memberId(), attempts, causeType, cause));
            }
        }, () -> log.warn("event=KAKAO_UNLINK_RETRY_FAILED failureId={} memberId={} reason=QUEUE_ROW_MISSING exceptionType={} details={}",
                pending.failureId(), pending.memberId(), KakaoUnlinkFailureClassifier.causeType(cause), FailureLogDetails.from(cause)));
    }

    private record PendingUnlink(Long failureId, Long memberId, String providerUserId) {
    }
}
