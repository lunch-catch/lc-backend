package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.model.CouponIssuanceRequest;
import com.launchcatch.coupon.model.CouponReservation;
import com.launchcatch.coupon.model.CouponReservationOutcome;
import com.launchcatch.coupon.repository.CouponBatchIssueRepository;
import com.launchcatch.coupon.repository.CouponBatchIssueRepository.NewCoupon;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.service.model.CouponBatchDeadline;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CouponBatchIssuanceServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private static final LocalDateTime NOW = DATE.atTime(12, 0);
    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final CouponBatchIssueRepository repository = mock(CouponBatchIssueRepository.class);
    private final MemberQueryService members = mock(MemberQueryService.class);
    private final WishlistQueryService wishes = mock(WishlistQueryService.class);
    private final CouponSequenceService sequences = mock(CouponSequenceService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneOffset.ofHours(9));
    private final CouponBatchIssuanceService service =
            new CouponBatchIssuanceService(events, repository, members, wishes, sequences, publisher, clock);
    private final CouponReservation reservation = new CouponReservation(81L, 1, "reservation");
    private final byte[] code = new byte[16];

    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(service, "dailyLimit", 3);
        ReflectionTestUtils.setField(service, "issueStartTime", LocalTime.of(11, 0));
        ReflectionTestUtils.setField(service, "issueEndTime", LocalTime.of(14, 0));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        CouponEvent event = CouponEvent.prepare(71L, DATE, 10, NOW, NOW.plusHours(2),
                new CouponEvent.Snapshot(61L, "가게", null, DiscountTargetType.ALL, DiscountType.AMOUNT,
                        1000, null, null, null, null));
        event.initializeCampaignState(true, NOW);
        ReflectionTestUtils.setField(event, "id", 81L);
        when(events.findAllForIssue(Set.of(81L))).thenReturn(List.of(event));
        when(members.findCurrentStatuses(Set.of(17L))).thenReturn(Map.of(17L, MemberStatus.ACTIVE));
        when(wishes.findWishedMemberIds(71L, Set.of(17L))).thenReturn(Set.of(17L));
        when(sequences.reserveAll(Map.of(81L, 1)))
                .thenReturn(Map.of(81L, List.of(new CouponReservationOutcome(reservation, null, false, false))));
    }

    @AfterEach
    void cleanUp() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 예약한_재고와_카운터를_반영하고_저장한_쿠폰의_응답과_이벤트를_만든다() {
        when(repository.issueSequences(Map.of(81L, List.of(1)), NOW)).thenReturn(1);
        when(repository.findCouponCodes(Map.of(81L, List.of(1)))).thenReturn(Map.of(81L, Map.of(1, code)));
        NewCoupon coupon = new NewCoupon(81L, 17L, code, NOW, NOW.plusHours(2), NOW);
        when(repository.insertCoupons(List.of(coupon))).thenReturn(List.of(1001L));
        CouponBatchDeadline deadline = new CouponBatchDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));

        CouponIssuanceOutcome result = service.issueBatch(List.of(request(17L)), deadline).getFirst();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.beforeCommit(false));

        assertThat(result.response().memberCouponId()).isEqualTo(1001L);
        assertThat(result.response().issueTime()).isEqualTo(NOW.atOffset(ZoneOffset.ofHours(9)));
        assertThat(result.reservation()).isEqualTo(reservation);
        assertThat(result.rejection()).isNull();
        assertThat(deadline.cancel()).isFalse();
        verify(repository).increaseCounters(DATE, Map.of(17L, 1), NOW);
        verify(publisher).publishEvent(new CouponIssuedEvent("coupon-issued:1001", NOW, 17L, 71L, 61L, 81L, 1001L));
    }

    @Test
    void 회원과_찜과_중복과_일일_한도를_판정하고_통과한_요청만_예약한다() {
        when(members.findCurrentStatuses(Set.of(17L, 18L, 19L, 20L))).thenReturn(Map.of(
                17L, MemberStatus.ACTIVE, 18L, MemberStatus.SUSPENDED,
                19L, MemberStatus.ACTIVE, 20L, MemberStatus.ACTIVE));
        when(wishes.findWishedMemberIds(71L, Set.of(17L, 19L, 20L))).thenReturn(Set.of(17L, 20L));
        when(repository.lockCounters(DATE, List.of(17L, 20L))).thenReturn(Map.of(20L, 3));
        when(repository.issueSequences(Map.of(81L, List.of(1)), NOW)).thenReturn(1);
        when(repository.findCouponCodes(Map.of(81L, List.of(1)))).thenReturn(Map.of(81L, Map.of(1, code)));
        when(repository.insertCoupons(anyList())).thenReturn(List.of(1001L));

        List<CouponIssuanceOutcome> results = service.issueBatch(List.of(
                request(17L), request(18L), request(19L), request(17L), request(20L)));

        assertThat(results.getFirst().response().memberCouponId()).isEqualTo(1001L);
        assertThat(results.subList(1, 5)).extracting(CouponIssuanceOutcome::rejection).containsExactly(
                CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED, CouponErrorCode.CAMPAIGN_NOT_WISHED,
                CouponErrorCode.EVENT_ALREADY_ISSUED, CouponErrorCode.DAILY_LIMIT_REACHED);
        verify(sequences).reserveAll(Map.of(81L, 1));
        verify(repository).increaseCounters(DATE, Map.of(17L, 1), NOW);
    }

    @Test
    void Redis_예약과_DB_재고가_어긋나면_이벤트를_차단하고_발급을_실패시킨다() {
        CouponException failure = CouponException.recoveryRequired(81L);
        when(sequences.rejectForRecovery(81L)).thenReturn(failure);

        assertThatThrownBy(() -> service.issueBatch(List.of(request(17L)))).isSameAs(failure);

        verify(sequences).block(81L, null);
        verifyNoInteractions(publisher);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).insertCoupons(anyList());
    }

    @Test
    void 순번을_못_받은_요청은_예약_대기와_복구와_소진을_구분한다() {
        when(members.findCurrentStatuses(Set.of(17L, 18L, 19L))).thenReturn(Map.of(
                17L, MemberStatus.ACTIVE, 18L, MemberStatus.ACTIVE, 19L, MemberStatus.ACTIVE));
        when(wishes.findWishedMemberIds(71L, Set.of(17L, 18L, 19L))).thenReturn(Set.of(17L, 18L, 19L));
        when(sequences.reserveAll(Map.of(81L, 3))).thenReturn(Map.of(81L, List.of(
                new CouponReservationOutcome(null, CouponErrorCode.ISSUANCE_BUSY, false, true),
                new CouponReservationOutcome(null, CouponErrorCode.ISSUANCE_BUSY, true, false),
                new CouponReservationOutcome(null, CouponErrorCode.INVENTORY_EXHAUSTED, false, false))));

        List<CouponIssuanceOutcome> results = service.issueBatch(List.of(request(17L), request(18L), request(19L)));

        assertThat(results.get(0).awaitingReservation()).isTrue();
        assertThat(results.get(0).recoveryRequired()).isFalse();
        assertThat(results.get(1).recoveryRequired()).isTrue();
        assertThat(results.get(1).awaitingReservation()).isFalse();
        assertThat(results.get(2).rejection()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED);
        assertThat(results).allSatisfy(result -> assertThat(result.reservation()).isNull());
        verifyNoInteractions(publisher);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).increaseCounters(any(), any(), any());
    }

    @Test
    void 없는_이벤트와_발급_종료_시각의_요청은_자격_조회_전에_거절한다() {
        List<CouponEvent> found = events.findAllForIssue(Set.of(81L));
        when(events.findAllForIssue(Set.of(81L, 82L))).thenReturn(found);

        List<CouponIssuanceOutcome> results = service.issueBatch(List.of(
                new CouponIssuanceRequest(17L, 82L, NOW),
                new CouponIssuanceRequest(18L, 81L, DATE.atTime(14, 0))));

        assertThat(results).extracting(CouponIssuanceOutcome::rejection)
                .containsExactly(CouponErrorCode.COUPON_NOT_FOUND, CouponErrorCode.COUPON_NOT_AVAILABLE);
        verifyNoInteractions(members, wishes, repository, publisher);
    }

    @Test
    void 확정_소진은_회원_자격_조회와_DB_쓰기_전에_거절한다() {
        when(sequences.soldOutEvents(Set.of(81L))).thenReturn(Set.of(81L));

        CouponIssuanceOutcome result = service.issueBatch(List.of(request(17L))).getFirst();

        assertThat(result.rejection()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED);
        verifyNoInteractions(members, wishes, repository, publisher);
    }

    @Test
    void 취소한_묶음은_DB와_Redis를_호출하지_않는다() {
        CouponBatchDeadline deadline = new CouponBatchDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        deadline.cancel();

        assertThatThrownBy(() -> service.issueBatch(List.of(request(17L)), deadline))
                .isInstanceOfSatisfying(CouponException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
        verifyNoInteractions(events, repository, sequences, members, wishes, publisher);
    }

    private CouponIssuanceRequest request(long memberId) {
        return new CouponIssuanceRequest(memberId, 81L, NOW);
    }
}
