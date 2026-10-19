package com.launchcatch.coupon.service;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.entity.CouponDailyLimit;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.CouponInventory;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponInventoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponIssuanceService {

    private final MemberCouponRepository memberCouponRepository;
    private final CouponDailyLimitRepository couponDailyLimitRepository;
    private final MemberQueryService memberQueryService;
    private final CouponEventRepository couponEventRepository;
    private final CouponInventoryRepository couponInventoryRepository;
    private final WishlistQueryService wishlistQueryService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    private static final String ISSUED_EVENT_ID_PREFIX = "coupon-issued:";

    @Value("${coupon.daily-limit:3}")
    private int dailyLimit;

    @Value("${coupon.issue-start-time:11:00}")
    private LocalTime issueStartTime;

    @Value("${coupon.issue-end-time:14:00}")
    private LocalTime issueEndTime;

    /**
     * 인증된 회원에게 당일 이벤트의 쿠폰 한 장을 발급한다.
     * 잠금은 회원의 일일 카운터, 이벤트, 미발급 재고 순서로 확보한다.
     * READ_COMMITTED를 사용해 잠금을 기다린 요청도 앞 요청이 커밋한 발급 기록을 확인한다.
     * 재고와 일일 횟수, 회원 쿠폰은 한 트랜잭션에서 변경하며 실패하면 함께 롤백한다.
     *
     * @param memberId 인증된 회원의 ID
     * @param eventId 발급받을 당일 쿠폰 이벤트의 ID
     * @return 저장된 발급 응답과 마지막 재고인 경우의 소진 통보 정보
     * @throws CouponException 이벤트나 회원, 중복 발급, 일일 한도 또는 재고 조건을 만족하지 못한 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result issue(Long memberId, Long eventId) {
        // 1. 한국 영업일의 카운터가 없으면 생성한다
        LocalDateTime requestTime = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));
        couponDailyLimitRepository.ensureCounter(memberId, requestTime.toLocalDate(), requestTime);
        CouponDailyLimit counter = couponDailyLimitRepository
                .findByMemberIdAndBusinessDate(memberId, requestTime.toLocalDate()).orElseThrow();

        // 2. 이벤트 운영 상태와 영업일, 시스템 발급 시간을 검사한다.
        CouponEvent event = couponEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));
        checkEvent(event, requestTime, now);

        // 3. 회원의 현재 계정 상태가 ACTIVE인지 확인한다.
        if (memberQueryService.findById(memberId).map(member -> member.status() != MemberStatus.ACTIVE).orElse(true)) {
            throw new CouponException(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
        }
        if (!wishlistQueryService.isWished(memberId, event.getCampaignId())) {
            throw new CouponException(CouponErrorCode.CAMPAIGN_NOT_WISHED);
        }

        // 4. 삭제된 쿠폰도 포함해 같은 이벤트에서 이미 발급받았는지 확인한다.
        if (memberCouponRepository.existsByCouponEventIdAndMemberId(eventId, memberId)) {
            throw new CouponException(CouponErrorCode.EVENT_ALREADY_ISSUED);
        }

        // 5. 일일 한도를 확인하고 발급 횟수를 1 늘린다.
        counter.increaseIssuedCount(dailyLimit);

        // 6. AVAILABLE 재고 중 sequence_no가 가장 작은 한 장을 잠근다.
        CouponInventory inventory = couponInventoryRepository
                .findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(eventId, CouponInventoryStatus.AVAILABLE)
                .orElseThrow(() -> new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED));

        // 7. 잠금을 기다리는 동안 발급 시간이 끝났을 수 있으므로 현재 시각을 다시 검사한다.
        checkEvent(event, requestTime, LocalDateTime.now(clock.withZone(ClockConfig.ZONE)));

        // 8. 재고를 발급 상태로 바꾸고 해당 재고 코드와 이벤트 사용 시간을 가진 회원 쿠폰을 저장한다.
        inventory.issue();
        MemberCoupon coupon = memberCouponRepository.saveAndFlush(MemberCoupon.issue(memberId, eventId, inventory.getCouponCode(),
                event.getUsableStartAt(), event.getUsableEndAt()));

        eventPublisher.publishEvent(new CouponIssuedEvent(
                ISSUED_EVENT_ID_PREFIX + coupon.getId(),
                coupon.getCreatedAt(),
                coupon.getMemberId(),
                event.getCampaignId(),
                event.getStoreId(),
                event.getId(),
                coupon.getId()));

        // 9. 저장된 쿠폰 ID와 createdAt으로 발급 결과를 구성한다.
        SoldOut soldOut = couponInventoryRepository.existsByCouponEventIdAndStatus(eventId, CouponInventoryStatus.AVAILABLE)
                ? null : new SoldOut(event.getCampaignId(), event.getBusinessDate(),
                        coupon.getCreatedAt().plusNanos(500).truncatedTo(ChronoUnit.MICROS));
        return new Result(new CouponIssueResponse(coupon.getId(),
                coupon.getCreatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime()), soldOut);
    }

    private void checkEvent(CouponEvent event, LocalDateTime requestTime, LocalDateTime now) {
        if (event.getStatus() != CouponEventStatus.ACTIVE
                || !event.getBusinessDate().equals(now.toLocalDate())
                || !requestTime.toLocalDate().equals(now.toLocalDate())
                || now.toLocalTime().isBefore(issueStartTime) || !now.toLocalTime().isBefore(issueEndTime)) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
    }

    // 소진으로 거절한 트랜잭션이 끝난 뒤 마지막 발급 시각을 읽어 누락된 소진 통보를 복구한다.
    @Transactional(readOnly = true)
    public Optional<SoldOut> findSoldOut(Long eventId) {
        return couponEventRepository.findById(eventId).flatMap(event ->
                memberCouponRepository.findLastIssuedAtByCouponEventId(eventId)
                        .map(issuedAt -> new SoldOut(event.getCampaignId(), event.getBusinessDate(), issuedAt)));
    }

    public record Result(CouponIssueResponse response, SoldOut soldOut) { }

    public record SoldOut(long campaignId, LocalDate businessDate, LocalDateTime soldOutAt) { }
}
