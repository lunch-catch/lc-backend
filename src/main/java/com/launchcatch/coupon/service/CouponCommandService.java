package com.launchcatch.coupon.service;

import com.launchcatch.adserving.contract.CandidateStateUpdater;
import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.contract.CouponRedeemedEvent;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.dto.CouponUseRequest;
import com.launchcatch.coupon.dto.CouponUseResponse;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository.QrReference;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.geo.GeoDistance;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final CouponEventRepository couponEventRepository;
    private final CouponIssuanceService couponIssuanceService;
    private final CouponQrImageEncoder imageEncoder;
    private final Clock clock;
    private final CouponUsageHistoryRepository couponUsageHistoryRepository;
    private final OwnerStatusQuery ownerStatusQuery;
    private final StoreQueryService storeQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final CandidateStateUpdater candidateStateUpdater;

    private static final double MAX_DISTANCE_METERS = 100;
    private static final String REDEEMED_EVENT_ID_PREFIX = "coupon-redeemed:";

    /*
     * 발급 트랜잭션 프록시가 커밋한 뒤에만 광고 후보에 소진을 알린다.
     * NEVER는 트랜잭션을 만들지 않으며 호출자가 연 트랜잭션 안에서 조정하는 것도 막는다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public CouponIssueResponse issue(Long memberId, Long eventId) {
        CouponIssuanceService.Result result;
        try {
            result = couponIssuanceService.issue(memberId, eventId);
        } catch (CouponException error) {
            if (error.getErrorCode() == CouponErrorCode.INVENTORY_EXHAUSTED) {
                retrySoldOut(eventId);
            }
            throw error;
        }
        if (result.soldOut() != null) {
            notifySoldOut(result.soldOut());
        }
        return result.response();
    }

    private void retrySoldOut(Long eventId) {
        try {
            couponIssuanceService.findSoldOut(eventId).ifPresent(this::notifySoldOut);
        } catch (RuntimeException error) {
            log.error("event=COUPON_SOLD_OUT_RECOVERY_FAILED couponEventId={}", eventId, error);
        }
    }

    private void notifySoldOut(CouponIssuanceService.SoldOut soldOut) {
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

    /*
     * 점주의 QR 사용 요청을 검증하고 쿠폰 상태와 사용 이력을 함께 확정한다.
     * 사용한 마지막 토큰을 보존하여 같은 QR의 재시도와 교체된 QR을 구분한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponUseResponse redeem(Long ownerId, CouponUseRequest request) {
        try {
            // 1. 현재 점주 상태를 확인한 뒤 토큰으로 쿠폰 ID와 QR 버전만 조회한다.
            checkOwner(ownerId);
            QrReference reference = memberCouponRepository.findQrReference(request.qrToken())
                    .orElseThrow(() -> new CouponException(CouponErrorCode.QR_NOT_VALID));
            // 2. QR 갱신·삭제·사용이 같은 쿠폰 행 잠금으로 직렬화되도록 최신 상태를 읽는다.
            MemberCoupon coupon = memberCouponRepository.findByIdForUpdate(reference.getId())
                    .orElseThrow(() -> new CouponException(CouponErrorCode.QR_NOT_VALID));
            CouponEvent event = couponEventRepository.findById(coupon.getCouponEventId())
                    .orElseThrow(() -> new CouponException(CommonErrorCode.INTERNAL_ERROR));
            if (!storeQueryService.canManageStore(ownerId, event.getStoreId())) {
                throw new CouponException(CouponErrorCode.COUPON_ACCESS_DENIED);
            }
            // 3. 현재 매장 권한을 확인한 점주에게만 실제 사용한 QR의 재요청을 구분한다.
            if (coupon.getStatus() == MemberCouponStatus.USED) {
                coupon.validateQrToken(request.qrToken(), reference.getQrVersion());
                throw new CouponException(CouponErrorCode.COUPON_ALREADY_USED);
            }
            checkMemberStatusActive(coupon);
            StoreInfo store = findStore(event.getStoreId());
            // 4. 잠금 대기 후 현재 시각으로 유효 시간을 판정한다.
            LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE)).truncatedTo(ChronoUnit.MICROS);
            if (GeoDistance.meters(request.latitude(), request.longitude(), store.latitude(), store.longitude())
                    > MAX_DISTANCE_METERS) {
                throw new CouponException(CouponErrorCode.LOCATION_NOT_ALLOWED);
            }
            // 5. 쿠폰 스냅샷으로 메뉴 한 개의 할인액을 계산하고 쿠폰을 사용 처리한다.
            int discount = event.calculateDiscount();
            coupon.redeem(request.qrToken(), reference.getQrVersion(), now);
            // 6. 사용 이력을 JPA로 저장하고 쿠폰 상태도 함께 반영하며 실패하면 둘 다 롤백한다.
            CouponUsageHistory history = couponUsageHistoryRepository.saveAndFlush(
                    CouponUsageHistory.record(coupon.getId(), event.getStoreId(), discount));
            eventPublisher.publishEvent(new CouponRedeemedEvent(
                    REDEEMED_EVENT_ID_PREFIX + coupon.getId(),
                    history.getCreatedAt(),
                    coupon.getMemberId(),
                    event.getCampaignId(),
                    event.getStoreId(),
                    event.getId(),
                    coupon.getId()));
            // MySQL DATETIME(6)과 같은 정밀도로 auditing 시각을 반올림한다.
            return new CouponUseResponse(coupon.getId(), history.getId(), discount,
                    history.getCreatedAt().plusNanos(500).truncatedTo(ChronoUnit.MICROS)
                            .atZone(ClockConfig.ZONE).toOffsetDateTime());
        } catch (CouponException error) {
            if (error.getErrorCode().getHttpStatus().is5xxServerError()) {
                log.error("event=COUPON_USAGE_DATA_INVALID ownerId={} reason={}", ownerId, error.getErrorCode().getCode());
            } else {
                log.warn("event=COUPON_USAGE_FAILED ownerId={} reason={}", ownerId, error.getErrorCode().getCode());
            }
            throw error;
        }
    }

    private void checkOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            throw new CouponException(CouponErrorCode.OWNER_ACCOUNT_NOT_ALLOWED);
        }
    }

    private StoreInfo findStore(Long storeId) {
        StoreInfo store = storeQueryService.findAllById(List.of(storeId)).stream()
                .findFirst()
                .orElseThrow(() -> new CouponException(CommonErrorCode.INTERNAL_ERROR));
        if (store.latitude() == null || store.longitude() == null) {
            throw new CouponException(CommonErrorCode.INTERNAL_ERROR);
        }
        return store;
    }
}
