package com.launchcatch.coupon.service;

import com.launchcatch.adserving.contract.CandidateStateUpdater;
import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.coupon.service.model.CouponIssuanceResult;
import com.launchcatch.coupon.service.model.CouponIssuanceRequest;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
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
    private final CouponLookUpService couponLookUpService;
    private final CouponQrImageEncoder imageEncoder;
    private final Clock clock;
    private final CandidateStateUpdater candidateStateUpdater;
    private final CouponSequenceService sequenceService;
    private final CouponSequenceRecoveryService sequenceRecoveryService;
    private final CouponIssueBatcher batcher;

    @Value("${coupon.issue.batch.wait-timeout:10s}")
    private Duration waitTimeout = Duration.ofSeconds(10);

    /*
     * 발급 트랜잭션이 커밋한 뒤에만 광고 후보에 소진을 알린다.
     * NEVER는 트랜잭션을 만들지 않으며 호출자가 연 트랜잭션 안에서 조정하는 것도 막는다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public CouponIssueResponse issue(Long memberId, Long eventId) {
        long deadlineNanos = System.nanoTime() + waitTimeout.toNanos();
        CouponIssuanceRequest request = null;
        boolean resumed = false;
        while (true) {
            try {
                sequenceService.checkAdmission(eventId);
                if (request == null) {
                    request = new CouponIssuanceRequest(memberId, eventId,
                            LocalDateTime.now(clock.withZone(ClockConfig.ZONE)));
                }
                CouponIssuanceResult result = batcher.submitAndAwait(request, deadlineNanos);
                if (result.soldOut()) {
                    retrySoldOut(eventId);
                }
                return result.response();
            } catch (CouponException error) {
                if (error.getErrorCode() == CouponErrorCode.INVENTORY_EXHAUSTED) {
                    retrySoldOut(eventId);
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

    /*
     * 소진으로 거절했거나 마지막 재고를 발급했을 때 DB에서 소진을 확인해 광고 후보에 알린다.
     * 조회가 실패해도 이미 정한 발급 결과는 바꾸지 않고 ERROR 로그만 남긴다.
     */
    private void retrySoldOut(Long eventId) {
        try {
            couponLookUpService.findSoldOut(eventId).ifPresent(this::notifySoldOut);
        } catch (RuntimeException error) {
            log.error("event=COUPON_SOLD_OUT_RECOVERY_FAILED couponEventId={}", eventId, error);
        }
    }

    // 광고 후보에 캠페인의 그날 소진을 알리며 실패해도 발급 결과는 바꾸지 않고 ERROR 로그만 남긴다.
    private void notifySoldOut(CouponLookUpService.SoldOut soldOut) {
        try {
            candidateStateUpdater.markSoldOut(soldOut.campaignId(), soldOut.businessDate(), soldOut.soldOutAt());
        } catch (RuntimeException error) {
            log.error("event=COUPON_SOLD_OUT_NOTIFICATION_FAILED campaignId={} businessDate={} soldOutAt={}",
                    soldOut.campaignId(), soldOut.businessDate(), soldOut.soldOutAt(), error);
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
            log.warn("event=COUPON_QR_GENERATION_FAILED memberId={} memberCouponId={} reason={}",
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
            log.warn("event=COUPON_DELETE_FAILED memberId={} memberCouponId={} reason={}",
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
