package com.launchcatch.coupon.service;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.coupon.CouponListFilter;
import com.launchcatch.coupon.config.CouponProperties;
import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.CannotIssueReason;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.EventState;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.Item;
import com.launchcatch.coupon.dto.CouponEventStatusResponse;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponInventoryRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponLookUpService {

    private final CouponProperties properties;

    private final MemberCouponRepository memberCouponRepository;
    private final CouponEventRepository couponEventRepository;
    private final CouponUsageHistoryRepository couponUsageHistoryRepository;
    private final CouponDailyLimitRepository couponDailyLimitRepository;
    private final CampaignQueryService campaignQueryService;
    private final MemberQueryService memberQueryService;
    private final Clock clock;
    private final StoreQueryService storeQueryService;
    private final WishlistQueryService wishlistQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final CouponInventoryRepository couponInventoryRepository;

    /**
     * 매장별 오늘 이벤트와 조회 시점의 발급 가능 상태를 입력 순서로 반환한다.
     * 재고와 회원 발급 이력은 일괄 조회하며 쿠폰과 재고, 발급 횟수는 변경하지 않는다.
     * @param memberId 인증된 회원 ID
     * @param storeIds 컨트롤러에서 검증한 1개 이상 100개 이하의 서로 다른 양수 매장 ID
     * @return 한국 서버 시각과 매장별 이벤트 상태
     */
    public CouponEventStatusResponse getEventStatus(Long memberId, List<Long> storeIds) {
        OffsetDateTime serverTime = OffsetDateTime.now(clock.withZone(ClockConfig.ZONE));
        LocalDate businessDate = serverTime.toLocalDate();

        Set<Long> existingStoreIds = storeQueryService.findAllById(storeIds).stream()
                .map(StoreInfo::storeId).collect(Collectors.toSet());
        List<Long> visibleStoreIds = storeIds.stream().filter(existingStoreIds::contains).toList();
        if (visibleStoreIds.isEmpty()) {
            return new CouponEventStatusResponse(serverTime, List.of());
        }
        List<CouponEvent> events = couponEventRepository.findByStoreIdInAndBusinessDateAndStatusIn(
                visibleStoreIds, businessDate, List.of(CouponEventStatus.ACTIVE, CouponEventStatus.SCHEDULED));
        Map<Long, CouponEvent> eventsByStore = findDuplicatedEvent(events, businessDate);
        List<Long> eventIds = events.stream().map(CouponEvent::getId).toList();

        Map<Long, Integer> quantities = findAvailableQuantities(eventIds);

        Set<Long> issuedEventIds = eventIds.isEmpty() ? Set.of()
                : Set.copyOf(memberCouponRepository.findIssuedEventIds(memberId, eventIds));

        Set<Long> campaignStoreIds = campaignQueryService.findByStoreIdIn(visibleStoreIds).stream()
                .filter(campaign -> campaign.status() == CampaignStatus.ACTIVE
                        || campaign.status() == CampaignStatus.SCHEDULED)
                .map(CampaignInfo::storeId).collect(Collectors.toSet());

        boolean accountEligible = memberQueryService.findById(memberId).map(MemberInfo::status)
                .filter(status -> status == MemberStatus.ACTIVE).isPresent();

        boolean dailyLimitReached = findIssuedCount(memberId, businessDate) >= properties.dailyLimit();

        Set<Long> wishedCampaignIds = Set.copyOf(wishlistQueryService.findWishedCampaignIds(memberId));

        List<Item> items = new ArrayList<>(visibleStoreIds.size());
        LocalDateTime now = serverTime.toLocalDateTime();
        for (Long storeId : visibleStoreIds) {
            items.add(createEventStatusItem(
                    storeId,
                    eventsByStore.get(storeId),
                    campaignStoreIds.contains(storeId),
                    quantities,
                    issuedEventIds,
                    wishedCampaignIds,
                    accountEligible,
                    dailyLimitReached,
                    now
            ));
        }
        return new CouponEventStatusResponse(serverTime, items);
    }

    /**
     * 본인의 삭제되지 않은 쿠폰을 조회 시점 상태로 필터링하고 발급 시각과 ID의 내림차순으로 반환한다.
     * 이벤트 스냅샷과 사용 이력을 일괄 조회하며, 반환할 미사용 쿠폰의 사용 기간이 끝났으면 만료 상태로 변경해 저장한다.
     *
     * @param memberId 인증된 회원의 ID
     * @param filter 명세에 정의된 상태 또는 사용 가능 여부 표현식이며, null이면 전체 조회
     * @param pageSize 컨트롤러에서 1 이상 100 이하로 검증한 조회 항목 수
     * @param pageToken 이전 응답의 커서이며, null이면 첫 페이지
     * @return 쿠폰 표시 정보와 다음 페이지 토큰
     */
    @Transactional
    public CursorPageResponse<CouponDetailResponse> getCoupons(
            Long memberId, String filter, int pageSize, String pageToken) {
        CouponListFilter listFilter = parseFilter(filter);
        CouponCursor cursor = parsePageToken(pageToken, listFilter);
        LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));

        List<MemberCoupon> coupons = findCoupons(memberId, listFilter, cursor, now, pageSize);
        boolean hasNext = coupons.size() > pageSize;
        List<MemberCoupon> page = hasNext ? coupons.subList(0, pageSize) : coupons;
        if (page.isEmpty()) {
            return CursorPageResponse.of(List.of(), null);
        }

        Map<Long, CouponEvent> events = findEvents(page);
        Map<Long, CouponUsageHistory> histories = findUsageHistories(page);
        expireCoupons(page, events, now);
        List<CouponDetailResponse> items = createCouponDetailResponses(page, events, histories, now);
        String nextPageToken = createNextPageToken(page, listFilter, hasNext);

        return CursorPageResponse.of(items, nextPageToken);
    }

    /**
     * 오늘의 일일 한도에 반영된 쿠폰 수와 설정된 일일 한도를 조회한다.
     * 발급 기록이 없으면 발급 횟수는 0이며, 발급 횟수가 한도 이상이면 한도에 도달한 것으로 반환한다.
     *
     * @param memberId 인증된 회원의 ID
     * @return 조회 영업일, 일일 한도에 반영된 쿠폰 수, 일일 한도와 한도 도달 여부
     */
    public CouponIssuanceLimitResponse getIssuanceLimit(Long memberId) {
        LocalDate businessDate = LocalDate.now(clock.withZone(ClockConfig.ZONE));
        int issuedCount = findIssuedCount(memberId, businessDate);
        return new CouponIssuanceLimitResponse(
                businessDate, issuedCount, properties.dailyLimit(), issuedCount >= properties.dailyLimit());
    }

    /**
     * 본인 쿠폰의 이벤트 스냅샷과 조회 시점의 상태, 사용 가능 여부 및 사용 결과를 조회한다.
     * 소유권을 확인할 수 없으면 접근을 거절하고, 본인 소유이지만 삭제된 쿠폰은 조회할 수 없다.
     * 미사용 쿠폰의 사용 기간이 끝났으면 만료 상태로 변경해 저장한다.
     *
     * @param memberId 인증된 회원의 ID
     * @param memberCouponId 조회할 회원 쿠폰의 ID
     * @return 가게와 할인 스냅샷, 쿠폰 상태, 사용 가능 시간 및 사용 완료 결과
     * @throws CouponException 본인 소유권을 확인할 수 없거나 쿠폰이 삭제됐거나 연결된 스냅샷 또는 사용 이력이 누락된 경우
     */
    @Transactional
    public CouponDetailResponse getDetail(Long memberId, Long memberCouponId) {
        MemberCoupon coupon = findOwnedCoupon(memberId, memberCouponId);
        CouponEvent event = couponEventRepository.findById(coupon.getCouponEventId())
                .orElseThrow(() -> missingCouponData("EVENT_SNAPSHOT_MISSING", coupon));
        LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));
        MemberCouponStatus state = resolveState(coupon, event, now);
        boolean usable = isUsable(coupon, state, now);
        CouponUsageHistory history = findUsageHistory(coupon, state);

        return CouponDetailResponse.from(coupon, event, state, usable, history);
    }

    /**
     * 회원의 누적 쿠폰 발급 수, 사용 수와 할인받은 금액의 합계를 조회한다.
     * 삭제한 쿠폰도 발급 수에 포함하며, 할인액은 사용 이력에 확정해 저장한 금액을 기준으로 한다.
     *
     * @param memberId 인증된 회원의 ID
     * @return 누적 발급 수, 사용 수와 할인액 합계이며, 이력이 없으면 각 값은 0
     */
    public CouponSummaryResponse getSummary(Long memberId) {
        return memberCouponRepository.findSummaryByMemberId(memberId);
    }

    // 이벤트가 없는 매장은 별도로 처리해 상태 계산과 스냅샷 변환에서 null을 반복 확인하지 않는다.
    private Item createEventStatusItem(
            Long storeId, CouponEvent event, boolean hasCampaign, Map<Long, Integer> quantities,
            Set<Long> issuedEventIds, Set<Long> wishedCampaignIds,
            boolean accountEligible, boolean dailyLimitReached, LocalDateTime now) {
        if (event == null) {
            return createNoEventItem(storeId, hasCampaign);
        }

        int quantity = quantities.getOrDefault(event.getId(), 0);
        EventState state = resolveEventState(event, quantity, now);
        boolean issued = issuedEventIds.contains(event.getId());
        boolean wished = wishedCampaignIds.contains(event.getCampaignId());
        CannotIssueReason reason = resolveCannotIssueReason(state, accountEligible, issued, dailyLimitReached, wished);
        return new Item(storeId, hasCampaign, event.getId(), state, quantity,
                event.getUsableStartAt().atZone(ClockConfig.ZONE).toOffsetDateTime(),
                event.getUsableEndAt().atZone(ClockConfig.ZONE).toOffsetDateTime(),
                issued, wished, reason == null, reason);
    }

    private Item createNoEventItem(Long storeId, boolean hasCampaign) {
        return new Item(storeId, hasCampaign, null, EventState.NO_EVENT, 0,
                null, null, false, false, false, CannotIssueReason.NO_EVENT);
    }

    // 매장당 조회 대상 이벤트가 한 건이라는 조건이 깨지면 대표 이벤트를 임의로 고르지 않는다.
    private Map<Long, CouponEvent> findDuplicatedEvent(List<CouponEvent> events, LocalDate businessDate) {
        Map<Long, CouponEvent> eventsByStore = new HashMap<>();
        for (CouponEvent event : events) {
            if (eventsByStore.putIfAbsent(event.getStoreId(), event) != null) {
                log.error("event=COUPON_EVENT_STATUS_FAILED reason=DUPLICATE_STORE_EVENT storeId={} businessDate={}",
                        event.getStoreId(), businessDate);
                throw new CouponException(CommonErrorCode.INTERNAL_ERROR);
            }
        }
        return eventsByStore;
    }

    // 이벤트가 없으면 집계를 생략하고 있으면 AVAILABLE 재고만 이벤트별로 한 번에 센다.
    private Map<Long, Integer> findAvailableQuantities(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        return couponEventRepository.findQuantitiesByEventIdsAndStatus(eventIds, CouponInventoryStatus.AVAILABLE)
                .stream().collect(Collectors.toMap(CouponEventRepository.InventoryQuantity::getEventId,
                        quantity -> Math.toIntExact(quantity.getQuantity())));
    }

    // 시스템 발급 종료와 쿠폰 사용 종료를 운영 상태와 재고보다 먼저 판단한다.
    private EventState resolveEventState(CouponEvent event, int remainingQuantity, LocalDateTime now) {
        if (!now.toLocalTime().isBefore(properties.issueEndTime()) || !now.isBefore(event.getUsableEndAt())) {
            return EventState.CLOSED;
        }
        if (now.toLocalTime().isBefore(properties.issueStartTime()) || event.getStatus() == CouponEventStatus.SCHEDULED) {
            return EventState.SCHEDULED;
        }
        return remainingQuantity == 0 ? EventState.SOLD_OUT : EventState.OPEN;
    }

    private CannotIssueReason resolveCannotIssueReason(
            EventState state, boolean accountEligible, boolean hasIssuedToday, boolean dailyLimitReached, boolean wished) {
        if (!accountEligible) {
            return CannotIssueReason.ACCOUNT_NOT_ELIGIBLE;
        }
        if (hasIssuedToday) {
            return CannotIssueReason.ALREADY_ISSUED;
        }
        if (dailyLimitReached) {
            return CannotIssueReason.DAILY_LIMIT_REACHED;
        }
        if (!wished) {
            return CannotIssueReason.NOT_WISHED;
        }
        if (state == EventState.SCHEDULED || state == EventState.CLOSED) {
            return CannotIssueReason.NOT_OPEN;
        }
        if (state == EventState.SOLD_OUT) {
            return CannotIssueReason.SOLD_OUT;
        }
        return null;
    }

    private int findIssuedCount(Long memberId, LocalDate businessDate) {
        return couponDailyLimitRepository.findIssuedCountByMemberIdAndBusinessDate(memberId, businessDate).orElse(0);
    }

    // 본인 소유 쿠폰을 조회하고 소유권 확인 실패와 삭제된 쿠폰을 구분해 거절한다.
    private MemberCoupon findOwnedCoupon(Long memberId, Long memberCouponId) {
        MemberCoupon coupon = memberCouponRepository.findByIdAndMemberId(memberCouponId, memberId)
                .orElseThrow(() -> {
                    log.info("event=COUPON_DETAIL_FAILED memberId={} memberCouponId={} errorCode={}",
                            memberId, memberCouponId, CouponErrorCode.COUPON_ACCESS_DENIED.getCode());
                    return new CouponException(CouponErrorCode.COUPON_ACCESS_DENIED);
                });
        if (coupon.getStatus() == MemberCouponStatus.REMOVED) {
            log.info("event=COUPON_DETAIL_FAILED memberId={} memberCouponId={} errorCode={}",
                    memberId, memberCouponId, CouponErrorCode.COUPON_NOT_FOUND.getCode());
            throw new CouponException(CouponErrorCode.COUPON_NOT_FOUND);
        }
        return coupon;
    }

    // 미사용 쿠폰의 사용 종료 시각이 지났으면 엔티티 상태를 만료로 변경한다.
    private MemberCouponStatus resolveState(MemberCoupon coupon, CouponEvent event, LocalDateTime now) {
        MemberCouponStatus previous = coupon.getStatus();
        coupon.changeToExpired(now);
        if (previous == MemberCouponStatus.ISSUED && coupon.getStatus() == MemberCouponStatus.EXPIRED) {
            if (event == null) {
                throw missingCouponData("EVENT_SNAPSHOT_MISSING", coupon);
            }
            eventPublisher.publishEvent(new CouponExpiredEvent("coupon-expired:" + coupon.getId(), coupon.getUsableEndAt(),
                    coupon.getMemberId(), event.getCampaignId(), event.getStoreId(), event.getId(), coupon.getId()));
        }
        return coupon.getStatus();
    }

    // 쿠폰이 미사용 상태이고 조회 시각이 사용 가능 시간 안인지 확인한다.
    private boolean isUsable(MemberCoupon coupon, MemberCouponStatus state, LocalDateTime now) {
        return state == MemberCouponStatus.ISSUED
                && !now.isBefore(coupon.getUsableStartAt())
                && now.isBefore(coupon.getUsableEndAt());
    }

    // 사용 완료 쿠폰의 확정된 사용 이력을 조회한다.
    private CouponUsageHistory findUsageHistory(MemberCoupon coupon, MemberCouponStatus state) {
        if (state != MemberCouponStatus.USED) {
            return null;
        }
        return couponUsageHistoryRepository.findByMemberCouponId(coupon.getId())
                .orElseThrow(() -> missingCouponData("USAGE_HISTORY_MISSING", coupon));
    }

    // 정합성 오류의 원인을 추적할 식별자는 서버 로그에만 남긴다.
    private CouponException missingCouponData(String reason, MemberCoupon coupon) {
        log.error("event=COUPON_DATA_INTEGRITY_FAILED reason={} errorCode={} memberCouponId={} couponEventId={}",
                reason, CommonErrorCode.INTERNAL_ERROR.getCode(), coupon.getId(), coupon.getCouponEventId());
        return new CouponException(CommonErrorCode.INTERNAL_ERROR);
    }

    // 허용한 필터 표현식을 조회 조건으로 변환한다.
    private CouponListFilter parseFilter(String filter) {
        return switch (filter) {
            case null -> CouponListFilter.ALL;
            case "usable = true" -> CouponListFilter.USABLE;
            case "state = \"ISSUED\"" -> CouponListFilter.ISSUED;
            case "state = \"USED\"" -> CouponListFilter.USED;
            case "state = \"EXPIRED\"" -> CouponListFilter.EXPIRED;
            default -> throw new CouponException(CommonErrorCode.INVALID_INPUT);
        };
    }

    // 토큰의 형식과 필터를 검증하고 다음 조회의 기준 시각과 ID를 꺼낸다.
    private CouponCursor parsePageToken(String pageToken, CouponListFilter listFilter) {
        if (pageToken == null) {
            return new CouponCursor(null, null);
        }
        PageCursor cursor = PageTokens.decode(pageToken);
        if (cursor == null || cursor.id() <= 0 || cursor.sortValue() == null) {
            throw new CouponException(CommonErrorCode.INVALID_INPUT);
        }
        String[] cursorValues = cursor.sortValue().split("\\|", -1);
        if (cursorValues.length != 2 || !cursorValues[1].equals(listFilter.name())) {
            throw new CouponException(CommonErrorCode.INVALID_INPUT);
        }
        try {
            return new CouponCursor(LocalDateTime.parse(cursorValues[0]), cursor.id());
        } catch (DateTimeParseException e) {
            throw new CouponException(CommonErrorCode.INVALID_INPUT, e);
        }
    }

    // 다음 페이지의 존재를 확인할 수 있도록 요청한 크기보다 한 건 더 조회한다.
    private List<MemberCoupon> findCoupons(
            Long memberId, CouponListFilter listFilter, CouponCursor cursor, LocalDateTime now, int pageSize) {
        return memberCouponRepository.findPage(memberId, listFilter, now, cursor.issueTime(), cursor.id(), pageSize + 1);
    }

    // 반환할 페이지의 이벤트 스냅샷을 일괄 조회하여 이벤트 ID로 찾을 수 있게 구성한다.
    private Map<Long, CouponEvent> findEvents(List<MemberCoupon> page) {
        List<Long> eventIds = page.stream().map(MemberCoupon::getCouponEventId).distinct().toList();
        return couponEventRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(CouponEvent::getId, Function.identity()));
    }

    // 사용 완료 쿠폰의 이력을 일괄 조회하여 회원 쿠폰 ID로 찾을 수 있게 구성한다.
    private Map<Long, CouponUsageHistory> findUsageHistories(List<MemberCoupon> page) {
        List<Long> usedCouponIds = page.stream().filter(coupon -> coupon.getStatus() == MemberCouponStatus.USED)
                .map(MemberCoupon::getId).toList();
        if (usedCouponIds.isEmpty()) {
            return Map.of();
        }
        return couponUsageHistoryRepository.findAllByMemberCouponIdIn(usedCouponIds).stream()
                .collect(Collectors.toMap(CouponUsageHistory::getMemberCouponId, Function.identity()));
    }

    // 반환할 페이지에서 사용 종료 시각에 도달한 미사용 쿠폰의 상태를 만료로 변경한다.
    private void expireCoupons(List<MemberCoupon> page, Map<Long, CouponEvent> events, LocalDateTime now) {
        for (MemberCoupon coupon : page) {
            resolveState(coupon, events.get(coupon.getCouponEventId()), now);
        }
    }

    // 이벤트 스냅샷과 사용 이력에 현재 상태와 사용 가능 여부를 합쳐 응답 목록을 구성한다.
    private List<CouponDetailResponse> createCouponDetailResponses(List<MemberCoupon> page,
            Map<Long, CouponEvent> events, Map<Long, CouponUsageHistory> histories, LocalDateTime now) {
        List<CouponDetailResponse> items = new ArrayList<>(page.size());
        for (MemberCoupon coupon : page) {
            CouponEvent event = events.get(coupon.getCouponEventId());
            if (event == null) {
                throw missingCouponData("EVENT_SNAPSHOT_MISSING", coupon);
            }
            MemberCouponStatus state = coupon.getStatus();
            boolean usable = isUsable(coupon, state, now);
            CouponUsageHistory history = histories.get(coupon.getId());
            if (state == MemberCouponStatus.USED && history == null) {
                throw missingCouponData("USAGE_HISTORY_MISSING", coupon);
            }
            items.add(CouponDetailResponse.from(coupon, event, state, usable, history));
        }
        return List.copyOf(items);
    }

    // 다음 페이지가 있을 때만 반환한 마지막 쿠폰의 발급 시각과 ID, 필터로 토큰을 만든다.
    private String createNextPageToken(List<MemberCoupon> page, CouponListFilter listFilter, boolean hasNext) {
        if (!hasNext) {
            return null;
        }
        MemberCoupon lastCoupon = page.getLast();
        return PageTokens.encode(new PageCursor(
                lastCoupon.getId(), lastCoupon.getCreatedAt() + "|" + listFilter.name()));
    }

    private record CouponCursor(LocalDateTime issueTime, Long id) {
    }

    /*
     * 소진으로 거절했거나 마지막 재고를 발급한 뒤 남은 재고가 없으면 소진 통보에 쓸 값을 돌려준다.
     * 소진 시각은 삭제한 쿠폰도 포함한 마지막 발급 시각이며 이벤트나 발급 기록이 없으면 빈 값을 돌려준다.
     */
    public Optional<SoldOut> findSoldOut(Long eventId) {
        if (couponInventoryRepository.existsByCouponEventIdAndStatus(eventId, CouponInventoryStatus.AVAILABLE)) {
            return Optional.empty();
        }
        return couponEventRepository.findById(eventId).flatMap(event ->
                memberCouponRepository.findLastIssuedAtByCouponEventId(eventId)
                        .map(issuedAt -> new SoldOut(event.getCampaignId(), event.getBusinessDate(), issuedAt)));
    }

    // 광고 후보에 소진을 알릴 캠페인과 영업일, 소진 시각이다.
    public record SoldOut(long campaignId, LocalDate businessDate, LocalDateTime soldOutAt) { }
}
