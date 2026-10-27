package com.lunchcatch.coupon.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.coupon.CouponQueryLimits;
import com.lunchcatch.coupon.contract.CouponRedeemedEvent;
import com.lunchcatch.coupon.dto.CouponUsageHistoryResponse;
import com.lunchcatch.coupon.dto.CouponUseRequest;
import com.lunchcatch.coupon.dto.CouponUseResponse;
import com.lunchcatch.coupon.dto.OwnerCouponDailyUsageResponse;
import com.lunchcatch.coupon.dto.OwnerCouponSummaryResponse;
import com.lunchcatch.coupon.entity.CouponEvent;
import com.lunchcatch.coupon.entity.CouponUsageHistory;
import com.lunchcatch.coupon.entity.MemberCoupon;
import com.lunchcatch.coupon.entity.MemberCouponStatus;
import com.lunchcatch.coupon.exception.CouponErrorCode;
import com.lunchcatch.coupon.exception.CouponException;
import com.lunchcatch.coupon.repository.CouponEventRepository;
import com.lunchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.lunchcatch.coupon.repository.MemberCouponRepository.QrReference;
import com.lunchcatch.coupon.repository.MemberCouponRepository;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.PageCursor;
import com.lunchcatch.global.response.PageTokens;
import com.lunchcatch.member.contract.MemberInfo;
import com.lunchcatch.member.contract.MemberQueryService;
import com.lunchcatch.member.contract.MemberStatus;
import com.lunchcatch.owner.contract.OwnerAccountStatus;
import com.lunchcatch.owner.contract.OwnerStatusQuery;
import com.lunchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerCouponService {

    private static final int HALF_MICROSECOND_NANOS = 500;

    private final OwnerStatusQuery ownerStatusQuery;
    private final CampaignQueryService campaignQueryService;
    private final CouponEventRepository couponEventRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponUsageHistoryRepository couponUsageHistoryRepository;
    private final MemberQueryService memberQueryService;
    private final StoreQueryService storeQueryService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    private static final String REDEEMED_EVENT_ID_PREFIX = "coupon-redeemed:";

    public OwnerCouponSummaryResponse summary(Long ownerId, long campaignId, LocalDate from, LocalDate to) {
        if (campaignId <= 0 || from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= CouponQueryLimits.MAX_SUMMARY_DAYS || to.equals(LocalDate.MAX)) {
            throw new IllegalArgumentException("campaignId must be positive and the inclusive date range must be 1 to "
                    + CouponQueryLimits.MAX_SUMMARY_DAYS + " days");
        }
        validateActiveOwner(ownerId);
        boolean owned = campaignQueryService.findAllById(List.of(campaignId)).stream()
                .anyMatch(campaign -> ownerId.equals(campaign.ownerId()));
        if (!owned) {
            log.info("event=OWNER_COUPON_SUMMARY_FAILED reason=CAMPAIGN_ACCESS_DENIED ownerId={} campaignId={} errorCode={}",
                    ownerId, campaignId, CouponErrorCode.COUPON_ACCESS_DENIED.getCode());
            throw new CouponException(CouponErrorCode.COUPON_ACCESS_DENIED);
        }

        var counts = couponEventRepository.findOwnerSummary(campaignId, from, to, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        return new OwnerCouponSummaryResponse(campaignId, from, to,
                counts.getTotalQuantity(), counts.getIssuedCount(), counts.getUsedCount(), counts.getRemainingQuantity());
    }

    // 매장 관리 권한을 확인한 뒤 실제 사용일의 건수를 조회하고 사용이 없는 날짜를 채운다.
    public OwnerCouponDailyUsageResponse getDailyUsages(Long ownerId, long storeId, LocalDate from, LocalDate to) {
        if (storeId <= 0 || from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= CouponQueryLimits.MAX_SUMMARY_DAYS || to.equals(LocalDate.MAX)) {
            throw new IllegalArgumentException("storeId must be positive and the inclusive date range must be 1 to "
                    + CouponQueryLimits.MAX_SUMMARY_DAYS + " days");
        }
        validateActiveOwner(ownerId);
        if (!storeQueryService.canManageStore(ownerId, storeId)) {
            throw new CouponException(CouponErrorCode.COUPON_ACCESS_DENIED);
        }

        var counts = couponUsageHistoryRepository.findDailyUsageCounts(
                        storeId, from.atStartOfDay(), to.plusDays(1).atStartOfDay()).stream()
                .collect(Collectors.toMap(CouponUsageHistoryRepository.DailyUsageCount::getBusinessDate,
                        CouponUsageHistoryRepository.DailyUsageCount::getUsedCount));
        var items = from.datesUntil(to.plusDays(1))
                .map(day -> new OwnerCouponDailyUsageResponse.Item(day, counts.getOrDefault(day, 0L)))
                .toList();
        long totalUsedCount = items.stream().mapToLong(OwnerCouponDailyUsageResponse.Item::usedCount).sum();
        return new OwnerCouponDailyUsageResponse(storeId, from, to, totalUsedCount, items);
    }

    /**
     * ACTIVE 점주의 매장 사용 이력을 한국 날짜 범위와 커서로 조회한다.
     *
     * @param ownerId 인증된 점주의 ID
     * @param storeId 조회할 매장 ID
     * @param from 조회 시작일이며, 해당 날짜 00:00을 포함한다
     * @param to 조회 종료일이며, 다음 날짜 00:00은 제외한다
     * @param pageSize 조회 항목 수이며, 1 이상 100 이하이다
     * @param pageToken 같은 매장과 날짜 범위에서 받은 다음 페이지 토큰이며, null이면 첫 페이지이다
     * @return 확정 사용 결과 목록과 다음 페이지 토큰
     */
    public CursorPageResponse<CouponUsageHistoryResponse> getUsages(
            Long ownerId, Long storeId, LocalDate from, LocalDate to, int pageSize, String pageToken) {
        validateActiveOwner(ownerId);
        if (!storeQueryService.canManageStore(ownerId, storeId)) {
            log.info("event=COUPON_USAGE_HISTORY_FAILED ownerId={} storeId={} reason=STORE_NOT_MANAGEABLE errorCode={}",
                    ownerId, storeId, AuthErrorCode.ROLE_NOT_ALLOWED.getCode());
            throw new CouponException(AuthErrorCode.ROLE_NOT_ALLOWED);
        }
        validateDateRange(from, to);
        DateRange dateRange = parseDateRange(from, to);
        UsageCursor cursor = parsePageToken(pageToken, storeId, from, to);

        List<CouponUsageHistory> histories = findUsageHistories(storeId, dateRange, cursor, pageSize);
        boolean hasNext = histories.size() > pageSize;
        List<CouponUsageHistory> page = hasNext ? histories.subList(0, pageSize) : histories;
        if (page.isEmpty()) {
            return CursorPageResponse.of(List.of(), null);
        }

        List<CouponUsageHistoryResponse> items = createUsageHistoryResponses(page);
        String nextPageToken = createNextPageToken(page, storeId, from, to, hasNext);

        return CursorPageResponse.of(items, nextPageToken);
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
            // 4. 잠금 대기 후 현재 시각으로 유효 시간을 판정한다.
            LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE)).truncatedTo(ChronoUnit.MICROS);
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
                    history.getCreatedAt().plusNanos(HALF_MICROSECOND_NANOS).truncatedTo(ChronoUnit.MICROS)
                            .atZone(ClockConfig.ZONE).toOffsetDateTime());
        } catch (CouponException error) {
            if (error.getErrorCode().getHttpStatus().is5xxServerError()) {
                log.error("event=COUPON_USAGE_FAILED reason=DATA_INVALID ownerId={} errorCode={}", ownerId, error.getErrorCode().getCode());
            } else {
                log.info("event=COUPON_USAGE_FAILED ownerId={} errorCode={}", ownerId, error.getErrorCode().getCode());
            }
            throw error;
        }
    }

    private void checkOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            throw new CouponException(CouponErrorCode.OWNER_ACCOUNT_NOT_ALLOWED);
        }
    }

    private void checkMemberStatusActive(MemberCoupon coupon) {
        MemberInfo memberInfo = memberQueryService.findById(coupon.getMemberId())
                .orElseThrow(() -> new CouponException(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED));

        if (memberInfo.status() != MemberStatus.ACTIVE) {
            throw new CouponException(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
        }
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.info("event=OWNER_COUPON_QUERY_FAILED reason=OWNER_NOT_ACTIVE errorCode={} ownerId={}",
                    AuthErrorCode.ROLE_NOT_ALLOWED.getCode(), ownerId);
            throw new CouponException(AuthErrorCode.ROLE_NOT_ALLOWED);
        }
    }

    // 조회 범위 검증하며 조회 기간의 길이는 제한하지 않는다.
    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || to.equals(LocalDate.MAX)) {
            throw new CouponException(CommonErrorCode.INVALID_INPUT);
        }
    }

    // 시작일 00:00 이상부터 종료일 다음 날 00:00 미만까지의 조회 범위로 변환한다.
    private DateRange parseDateRange(LocalDate from, LocalDate to) {
        return new DateRange(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    // 커서의 형식과 매장 및 조회 기간이 이번 요청과 일치하는지 검증한다.
    private UsageCursor parsePageToken(String pageToken, Long storeId, LocalDate from, LocalDate to) {
        if (pageToken == null) {
            return new UsageCursor(null, null);
        }
        PageCursor cursor = PageTokens.decode(pageToken);
        if (cursor == null || cursor.id() <= 0 || cursor.sortValue() == null) {
            throw new CouponException(CommonErrorCode.INVALID_INPUT);
        }
        String[] values = cursor.sortValue().split("\\|", -1);
        if (values.length != 4 || !values[1].equals(storeId.toString())
                || !values[2].equals(from.toString()) || !values[3].equals(to.toString())) {
            throw new CouponException(CommonErrorCode.INVALID_INPUT);
        }
        try {
            LocalDateTime useTime = LocalDateTime.parse(values[0]);
            if (useTime.toLocalDate().isBefore(from) || useTime.toLocalDate().isAfter(to)) {
                throw new CouponException(CommonErrorCode.INVALID_INPUT);
            }
            return new UsageCursor(useTime, cursor.id());
        } catch (DateTimeException e) {
            throw new CouponException(CommonErrorCode.INVALID_INPUT, e);
        }
    }

    // 다음 페이지의 존재를 확인할 수 있도록 요청한 크기보다 한 건 더 조회한다.
    private List<CouponUsageHistory> findUsageHistories(
            Long storeId, DateRange dateRange, UsageCursor cursor, int pageSize) {
        return couponUsageHistoryRepository.findPage(storeId, dateRange.fromInclusive(), dateRange.toExclusive(),
                cursor.useTime(), cursor.id(), pageSize + 1);
    }

    // 저장된 확정 할인액과 사용 시각으로 응답 목록을 구성한다.
    private List<CouponUsageHistoryResponse> createUsageHistoryResponses(List<CouponUsageHistory> page) {
        return page.stream()
                .map(history -> new CouponUsageHistoryResponse(history.getId(), history.getMemberCouponId(),
                        history.getDiscountAmount(), history.getCreatedAt().atZone(ClockConfig.ZONE).toOffsetDateTime()))
                .toList();
    }

    // 다음 페이지가 있을 때만 마지막 사용 이력과 조회 조건으로 토큰을 만든다.
    private String createNextPageToken(
            List<CouponUsageHistory> page, Long storeId, LocalDate from, LocalDate to, boolean hasNext) {
        if (!hasNext) {
            return null;
        }
        CouponUsageHistory last = page.getLast();
        return PageTokens.encode(new PageCursor(last.getId(),
                last.getCreatedAt() + "|" + storeId + "|" + from + "|" + to));
    }

    private record DateRange(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
    }

    private record UsageCursor(LocalDateTime useTime, Long id) {
    }
}
