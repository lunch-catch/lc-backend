package com.launchcatch.coupon.service;

import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.coupon.model.CouponIssuanceResult;
import com.launchcatch.coupon.model.CouponIssuanceRequest;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class CouponCommandService {

    private final MemberCouponRepository memberCouponRepository;
    private final CouponDailyLimitRepository couponDailyLimitRepository;
    private final MemberQueryService memberQueryService;
    private final CouponQrImageEncoder imageEncoder;
    private final Clock clock;
    private final CouponSoldOutNotificationService soldOutNotifications;
    private final CouponSequenceService sequenceService;
    private final CouponSequenceRecoveryService sequenceRecoveryService;
    private final CouponIssueBatcher batcher;

    private final AtomicInteger activeIssueRequests = new AtomicInteger();

    @Value("${coupon.issue.batch.queue-capacity:2000}")
    private int requestCapacity = 2000;

    @Value("${coupon.issue.batch.wait-timeout:10s}")
    private Duration waitTimeout = Duration.ofSeconds(10);

    /*
     * 소진 결과는 DB 재확인 없이 반환하며 누락 통보의 접수만 수행한다.
     * NEVER는 트랜잭션을 만들지 않으며 호출자가 연 트랜잭션 안에서 조정하는 것도 막는다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public CouponIssueResponse issue(Long memberId, Long eventId) {
        return issue(new CouponIssuanceRequest(memberId, eventId,
                LocalDateTime.now(clock.withZone(ClockConfig.ZONE))), System.nanoTime() + waitTimeout.toNanos());
    }

    @Transactional(propagation = Propagation.NEVER)
    public CouponIssueResponse issue(CouponIssuanceRequest request, long deadlineNanos) {
        if (activeIssueRequests.incrementAndGet() > requestCapacity) {
            activeIssueRequests.decrementAndGet();
            rejectIfConfirmedSoldOut(request.eventId());
            throw new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        }
        try {
            return issueWithinDeadline(request, deadlineNanos);
        } finally {
            activeIssueRequests.decrementAndGet();
        }
    }

    // 접수 상한에 닿아도 확정 소진은 혼잡 응답보다 먼저 반환하며 복구는 작업자에게 맡긴다.
    @Transactional(propagation = Propagation.NEVER)
    public void rejectIfConfirmedSoldOut(long eventId) {
        try {
            if (!sequenceService.isConfirmedSoldOut(eventId)) {
                return;
            }
        } catch (CouponException error) {
            if (error.getErrorCode() == CouponErrorCode.ISSUANCE_BUSY) {
                return;
            }
            throw error;
        }
        soldOutNotifications.request(eventId);
        throw new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
    }

    private CouponIssueResponse issueWithinDeadline(CouponIssuanceRequest request, long deadlineNanos) {
        long eventId = request.eventId();
        boolean resumed = false;
        while (true) {
            try {
                sequenceService.checkAdmission(eventId);
                if (sequenceService.isConfirmedSoldOut(eventId)) {
                    throw new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
                }
                CouponIssuanceResult result = batcher.submitAndAwait(request, deadlineNanos);
                return result.response();
            } catch (CouponException error) {
                if (error.getErrorCode() == CouponErrorCode.INVENTORY_EXHAUSTED) {
                    soldOutNotifications.request(eventId);
                }
                if (!resumed && error.getErrorCode() == CouponErrorCode.ISSUANCE_BUSY && sequenceService.needsRecovery(eventId)) {
                    boolean recovered = sequenceRecoveryService.recover(eventId, deadlineNanos);
                    if (error.canResumeIssuance(eventId) && recovered
                            && !Thread.currentThread().isInterrupted() && deadlineNanos - System.nanoTime() > 0) {
                        resumed = true;
                        continue;
                    }
                }
                throw error;
            } catch (RuntimeException error) {
                if (sequenceService.needsRecovery(eventId)) {
                    sequenceRecoveryService.recover(eventId, deadlineNanos);
                }
                throw error;
            }
        }
    }

    // 본인 쿠폰을 잠가 현재 회원 상태와 사용 조건을 확인하고 QR을 교체한다.
    public CouponQrResponse generateQr(Long memberId, Long memberCouponId) {
        try {
            MemberCoupon coupon = findOwnedCoupon(memberId, memberCouponId);
            checkMemberStatusActive(coupon);

            LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));
            coupon.renewQr(UUID.randomUUID(), now);
            return new CouponQrResponse(imageEncoder.encode(coupon.getQrToken()),
                    coupon.getQrExpiresAt().atZone(ClockConfig.ZONE).toOffsetDateTime());
        } catch (CouponException e) {
            log.info("event=COUPON_QR_GENERATION_FAILED memberId={} memberCouponId={} errorCode={}",
                    memberId, memberCouponId, e.getErrorCode().getCode());
            throw e;
        }
    }

    private void checkMemberStatusActive(MemberCoupon coupon) {
        MemberInfo memberInfo = memberQueryService.findById(coupon.getMemberId())
                .orElseThrow(() -> new CouponException(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED));

        if (memberInfo.status() != MemberStatus.ACTIVE) {
            throw new CouponException(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
        }
    }

    /**
     * 본인 쿠폰의 행 잠금을 확보하고 소프트 삭제하며 이미 삭제된 본인 쿠폰의 재요청은 허용한다.
     * 처음 삭제한 쿠폰은 발급받은 날짜의 일일 발급 횟수를 1 줄이며 발급 기록과 재고는 유지한다.
     *
     * @param memberId 인증된 회원의 ID
     * @param memberCouponId 삭제할 회원 쿠폰의 ID
     * @throws CouponException 본인 소유권을 확인할 수 없거나 사용 완료된 쿠폰인 경우
     */
    public void deleteCoupon(Long memberId, Long memberCouponId) {
        removeOwnedCoupon(memberId, memberCouponId);
    }

    // 본인 쿠폰을 처음 삭제했으면 발급받은 날짜의 발급 횟수를 차감한다.
    private void removeOwnedCoupon(Long memberId, Long memberCouponId) {
        MemberCoupon coupon;
        try {
            coupon = findOwnedCoupon(memberId, memberCouponId);
            if (!coupon.remove()) {
                return;
            }
        } catch (CouponException e) {
            log.info("event=COUPON_DELETE_FAILED memberId={} memberCouponId={} errorCode={}",
                    memberId, memberCouponId, e.getErrorCode().getCode());
            throw e;
        }
        LocalDate businessDate = coupon.getCreatedAt().toLocalDate();
        couponDailyLimitRepository.findByMemberIdAndBusinessDate(memberId, businessDate)
                .ifPresent(dailyLimit -> dailyLimit.decreaseIssuedCount());
    }

    // 본인 쿠폰의 행 잠금을 확보하고 소유권을 확인할 수 없으면 접근을 거절한다.
    private MemberCoupon findOwnedCoupon(Long memberId, Long memberCouponId) {
        return memberCouponRepository.findByIdAndMemberId(memberCouponId, memberId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_ACCESS_DENIED));
    }

}
