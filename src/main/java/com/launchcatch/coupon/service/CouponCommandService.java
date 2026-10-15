package com.launchcatch.coupon.service;

import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.dto.CouponQrResponse;
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
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final CouponInventoryRepository couponInventoryRepository;
    private final CouponQrImageEncoder imageEncoder;
    private final Clock clock;

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
     * 찜 조회와 광고 후보 소진 갱신은 #163 연동 전까지 생략한다.
     *
     * @param memberId 인증된 회원의 ID
     * @param eventId 발급받을 당일 쿠폰 이벤트의 ID
     * @return 저장된 회원 쿠폰 ID와 한국 시간 기준 발급 시각
     * @throws CouponException 이벤트나 회원, 중복 발급, 일일 한도 또는 재고 조건을 만족하지 못한 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponIssueResponse issue(Long memberId, Long eventId) {
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
        // TODO(#163): 공개 contract가 제공되면 현재 유효한 찜을 조회하고 없으면 COUPON-004로 거절한다.

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
        // TODO(#163): 마지막 재고 발급 시 해당 캠페인과 영업일의 광고 후보에 소진을 반영하고 실패 시 함께 롤백한다.

        // 9. 저장된 쿠폰 ID와 createdAt으로 발급 결과를 구성한다.
        return new CouponIssueResponse(coupon.getId(), coupon.getCreatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime());
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

    private void checkEvent(CouponEvent event, LocalDateTime requestTime, LocalDateTime now) {
        if (event.getStatus() != CouponEventStatus.ACTIVE
                || !event.getBusinessDate().equals(now.toLocalDate())
                || !requestTime.toLocalDate().equals(now.toLocalDate())
                || now.toLocalTime().isBefore(issueStartTime) || !now.toLocalTime().isBefore(issueEndTime)) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
    }
}
