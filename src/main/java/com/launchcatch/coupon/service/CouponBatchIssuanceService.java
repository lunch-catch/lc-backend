package com.launchcatch.coupon.service;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.repository.CouponBatchIssueRepository;
import com.launchcatch.coupon.repository.CouponBatchIssueRepository.NewCoupon;
import com.launchcatch.coupon.repository.CouponBatchIssueRepository.Pair;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.service.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.service.model.CouponIssuanceRequest;
import com.launchcatch.coupon.service.model.CouponReservation;
import com.launchcatch.coupon.service.model.CouponReservationOutcome;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 쿠폰 발급의 묶음 경로에서 트랜잭션 하나로 여러 요청을 처리하는 서비스다.
 * CouponIssueBatcher의 작업자 스레드만 부르며 요청 스레드는 이 서비스를 직접 부르지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CouponBatchIssuanceService {

    private static final String ISSUED_EVENT_ID_PREFIX = "coupon-issued:";

    private final CouponEventRepository couponEventRepository;
    private final CouponBatchIssueRepository batchRepository;
    private final MemberQueryService memberQueryService;
    private final WishlistQueryService wishlistQueryService;
    private final CouponSequenceService sequenceService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Value("${coupon.daily-limit:3}")
    private int dailyLimit;

    @Value("${coupon.issue-start-time:11:00}")
    private LocalTime issueStartTime;

    @Value("${coupon.issue-end-time:14:00}")
    private LocalTime issueEndTime;

    /**
     * 여러 발급 요청을 한 READ_COMMITTED 트랜잭션에서 처리하고 요청 순서대로 결과를 돌려준다.
     *
     * <p>처리 순서는 아래와 같다.
     * 1. 묶음에 든 이벤트를 ID 순으로 공유 잠금하고 이벤트 상태와 발급 시간을 검사한다.
     * 2. Redis에서 이벤트의 소진 여부를 읽어 소진이면 다른 판정보다 먼저 소진으로 거절한다.
     * 3. 회원 상태는 묶음당 한 번, 찜은 캠페인당 한 번 조회해 자격이 없는 요청을 거절한다.
     * 4. 남은 회원의 일일 카운터를 만들고 회원 ID 순으로 잠근 뒤 기존 발급 기록을 읽는다.
     *    이 값으로 요청 순서대로 중복과 일일 한도를 판정하며 같은 묶음 안의 앞 요청도 함께 센다.
     * 5. 판정을 통과한 요청 수만큼만 이벤트마다 Redis 호출 한 번으로 순번을 예약한다.
     * 6. 예약한 순번의 재고를 이벤트마다 한 문장으로 ISSUED로 바꾸고 쿠폰 코드를 읽는다.
     * 7. 카운터를 늘리고 회원 쿠폰을 다건 INSERT 한 문장으로 저장한 뒤 발급 이벤트를 발행한다.
     *
     * <p>업무 거절은 쓰기 전에 걸러 같은 묶음의 다른 요청에 영향을 주지 않는다.
     * 판정을 통과했지만 순번을 받지 못한 요청이 있어도 같은 회원의 뒤 요청은 한도 판정을 다시 하지 않는다.
     * 바꾼 재고 행 수가 예약 수와 다르면 Redis와 DB가 어긋난 것이므로 이벤트를 차단하고 묶음 전체를 실패시킨다.
     * 본문에서 예상하지 못한 오류가 나면 묶음 전체를 롤백하며 호출자는 묶음을 나누거나 자동으로 재시도하지 않는다.
     * 롤백되면 reserveAll이 등록한 트랜잭션 동기화가 받은 순번을 Redis에 반납한다.
     *
     * @param requests 묶음에 담긴 발급 요청
     * @return 요청과 같은 순서의 결과
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<CouponIssuanceOutcome> issueBatch(List<CouponIssuanceRequest> requests) {
        // 요청과 같은 위치에 결과와 예약을 채우며 결과가 null이면 아직 판정 중인 요청이다.
        CouponIssuanceOutcome[] outcomes = new CouponIssuanceOutcome[requests.size()];
        CouponReservation[] reservations = new CouponReservation[requests.size()];

        // 1. 이벤트를 공유 잠금하고 운영 상태와 발급 시간을 검사한다.
        Set<Long> eventIds = requests.stream().map(CouponIssuanceRequest::eventId).collect(Collectors.toCollection(TreeSet::new));
        Map<Long, CouponEvent> events = couponEventRepository.findAllForIssue(eventIds).stream()
                .collect(Collectors.toMap(CouponEvent::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));
        for (int i = 0; i < requests.size(); i++) {
            CouponEvent event = events.get(requests.get(i).eventId());
            if (event == null) {
                outcomes[i] = CouponIssuanceOutcome.rejected(CouponErrorCode.COUPON_NOT_FOUND);
            } else if (!isIssuable(event, requests.get(i).requestTime(), now)) {
                outcomes[i] = CouponIssuanceOutcome.rejected(CouponErrorCode.COUPON_NOT_AVAILABLE);
            }
        }

        // 2. 소진된 이벤트는 회원·중복·한도보다 먼저 소진으로 거절한다.
        Set<Long> soldOut = new HashSet<>();
        for (Long eventId : pendingEvents(requests, outcomes)) {
            if (sequenceService.isSoldOut(eventId)) {
                soldOut.add(eventId);
            }
        }
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] == null && soldOut.contains(requests.get(i).eventId())) {
                outcomes[i] = CouponIssuanceOutcome.rejected(CouponErrorCode.INVENTORY_EXHAUSTED);
            }
        }

        // 3. 회원 상태는 묶음당 한 번, 찜은 캠페인당 한 번 공개 계약으로 조회한다.
        Set<Long> memberIds = pendingMembers(requests, outcomes);
        Map<Long, MemberStatus> statuses = memberIds.isEmpty() ? Map.of()
                : memberQueryService.findCurrentStatuses(memberIds);
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] == null && statuses.get(requests.get(i).memberId()) != MemberStatus.ACTIVE) {
                outcomes[i] = CouponIssuanceOutcome.rejected(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
            }
        }
        Map<Long, Set<Long>> membersByCampaign = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] == null) {
                long campaignId = events.get(requests.get(i).eventId()).getCampaignId();
                membersByCampaign.computeIfAbsent(campaignId, key -> new HashSet<>()).add(requests.get(i).memberId());
            }
        }
        Map<Long, Set<Long>> wished = new HashMap<>();
        membersByCampaign.forEach((campaignId, members) ->
                wished.put(campaignId, wishlistQueryService.findWishedMemberIds(campaignId, members)));
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] == null) {
                long campaignId = events.get(requests.get(i).eventId()).getCampaignId();
                if (!wished.get(campaignId).contains(requests.get(i).memberId())) {
                    outcomes[i] = CouponIssuanceOutcome.rejected(CouponErrorCode.CAMPAIGN_NOT_WISHED);
                }
            }
        }

        // 4. 자격을 통과한 회원의 카운터를 만들고 회원 ID 순으로 잠근 뒤 중복과 일일 한도를 판정한다.
        LocalDate businessDate = now.toLocalDate();
        List<Long> counterMembers = new ArrayList<>(new TreeSet<>(pendingMembers(requests, outcomes)));
        Map<Long, Integer> counts = new HashMap<>();
        Set<Pair> issued = new HashSet<>();
        if (!counterMembers.isEmpty()) {
            batchRepository.ensureCounters(businessDate, counterMembers, now);
            counts.putAll(batchRepository.lockCounters(businessDate, counterMembers));
            issued.addAll(batchRepository.findIssuedPairs(pendingEvents(requests, outcomes), counterMembers));
        }
        Map<Long, Integer> eligibleCounts = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] != null) {
                continue;
            }
            CouponIssuanceRequest request = requests.get(i);
            if (!issued.add(new Pair(request.eventId(), request.memberId()))) {
                outcomes[i] = CouponIssuanceOutcome.rejected(CouponErrorCode.EVENT_ALREADY_ISSUED);
            } else if (counts.getOrDefault(request.memberId(), 0) + eligibleCounts.getOrDefault(request.memberId(), 0)
                    >= dailyLimit) {
                outcomes[i] = CouponIssuanceOutcome.rejected(CouponErrorCode.DAILY_LIMIT_REACHED);
            } else {
                eligibleCounts.merge(request.memberId(), 1, Integer::sum);
            }
        }

        /*
         * 5. 판정을 통과한 요청 수만큼만 이벤트마다 Redis 호출 한 번으로 예약한다.
         * 거절할 요청의 순번을 잡지 않아 같은 순간의 다른 묶음이 재고를 못 받는 일을 줄인다.
         */
        Map<Long, List<Integer>> eligibleByEvent = new LinkedHashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] == null) {
                eligibleByEvent.computeIfAbsent(requests.get(i).eventId(), key -> new ArrayList<>()).add(i);
            }
        }
        Map<Long, Integer> increments = new HashMap<>();
        for (Map.Entry<Long, List<Integer>> entry : eligibleByEvent.entrySet()) {
            List<Integer> indexes = entry.getValue();
            List<CouponReservationOutcome> reserved = sequenceService.reserveAll(entry.getKey(), indexes.size());
            for (int k = 0; k < indexes.size(); k++) {
                int i = indexes.get(k);
                if (reserved.get(k).reservation() == null) {
                    outcomes[i] = reserved.get(k).recoveryRequired() ? CouponIssuanceOutcome.requiringRecovery()
                            : CouponIssuanceOutcome.rejected(reserved.get(k).rejection());
                } else {
                    reservations[i] = reserved.get(k).reservation();
                    increments.merge(requests.get(i).memberId(), 1, Integer::sum);
                }
            }
        }
        // 모든 요청이 거절됐으면 쓸 것이 없으므로 카운터 행 생성만 커밋하고 끝낸다.
        if (increments.isEmpty()) {
            return List.of(outcomes);
        }

        // 6. 이벤트마다 배정한 순번의 재고를 한 문장으로 갱신하고 쿠폰 코드를 읽는다.
        Map<Long, List<Integer>> sequencesByEvent = new LinkedHashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            if (reservations[i] != null) {
                sequencesByEvent.computeIfAbsent(requests.get(i).eventId(), key -> new ArrayList<>())
                        .add(reservations[i].sequence());
            }
        }
        // 바꾼 행 수가 예약 수와 다르면 다른 요청이 같은 순번을 이미 썼다는 뜻이라 묶음을 멈추고 복구를 맡긴다.
        Map<Long, Map<Integer, byte[]>> codes = new HashMap<>();
        for (Map.Entry<Long, List<Integer>> entry : sequencesByEvent.entrySet()) {
            if (batchRepository.issueSequences(entry.getKey(), entry.getValue(), now) != entry.getValue().size()) {
                sequenceService.block(entry.getKey(), null);
                throw sequenceService.rejectForRecovery(entry.getKey());
            }
            codes.put(entry.getKey(), batchRepository.findCouponCodes(entry.getKey(), entry.getValue()));
        }

        // 7. 카운터를 늘리고 회원 쿠폰을 한 문장으로 저장한다.
        batchRepository.increaseCounters(businessDate, increments, now);
        LocalDateTime issuedAt = LocalDateTime.now(clock.withZone(ClockConfig.ZONE));
        List<Integer> acceptedIndexes = new ArrayList<>();
        List<NewCoupon> coupons = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            if (reservations[i] != null) {
                CouponIssuanceRequest request = requests.get(i);
                CouponEvent event = events.get(request.eventId());
                acceptedIndexes.add(i);
                coupons.add(new NewCoupon(request.eventId(), request.memberId(),
                        codes.get(request.eventId()).get(reservations[i].sequence()),
                        event.getUsableStartAt(), event.getUsableEndAt(), issuedAt));
            }
        }
        List<Long> couponIds = batchRepository.insertCoupons(coupons);

        // 생성된 ID는 넣은 순서와 같으므로 acceptedIndexes로 원래 요청 위치에 결과를 돌려놓는다.

        for (int k = 0; k < acceptedIndexes.size(); k++) {
            int i = acceptedIndexes.get(k);
            CouponIssuanceRequest request = requests.get(i);
            CouponEvent event = events.get(request.eventId());
            long couponId = couponIds.get(k);
            eventPublisher.publishEvent(new CouponIssuedEvent(ISSUED_EVENT_ID_PREFIX + couponId, issuedAt,
                    request.memberId(), event.getCampaignId(), event.getStoreId(), event.getId(), couponId));
            outcomes[i] = CouponIssuanceOutcome.issued(new CouponIssueResponse(couponId,
                    issuedAt.atZone(ClockConfig.ZONE).toOffsetDateTime()), reservations[i]);
        }
        return List.of(outcomes);
    }

    /*
     * 이벤트가 ACTIVE이고 오늘 회차이며 요청 시각이 발급 시간 안이면 true를 돌려준다.
     * 단건 경로의 checkEvent와 같은 조건을 쓰며 예외 대신 판정 결과를 돌려준다.
     */
    private boolean isIssuable(CouponEvent event, LocalDateTime requestTime, LocalDateTime now) {
        return event.getStatus() == CouponEventStatus.ACTIVE
                && event.getBusinessDate().equals(now.toLocalDate())
                && requestTime.toLocalDate().equals(now.toLocalDate())
                && !requestTime.toLocalTime().isBefore(issueStartTime)
                && requestTime.toLocalTime().isBefore(issueEndTime);
    }

    // 아직 판정이 끝나지 않은 요청의 이벤트 ID를 오름차순으로 모은다.
    private static Set<Long> pendingEvents(List<CouponIssuanceRequest> requests, CouponIssuanceOutcome[] outcomes) {
        Set<Long> events = new TreeSet<>();
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] == null) {
                events.add(requests.get(i).eventId());
            }
        }
        return events;
    }

    // 아직 판정이 끝나지 않은 요청의 회원 ID를 모은다.
    private static Set<Long> pendingMembers(List<CouponIssuanceRequest> requests, CouponIssuanceOutcome[] outcomes) {
        Set<Long> members = new HashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            if (outcomes[i] == null) {
                members.add(requests.get(i).memberId());
            }
        }
        return members;
    }
}
