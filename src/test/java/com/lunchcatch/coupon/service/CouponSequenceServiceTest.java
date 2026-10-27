package com.lunchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.coupon.CouponTestProperties;
import com.lunchcatch.coupon.exception.CouponErrorCode;
import com.lunchcatch.coupon.exception.CouponException;
import com.lunchcatch.coupon.repository.CouponSequenceRepository;
import com.lunchcatch.coupon.model.CouponIssuanceOutcome;
import com.lunchcatch.coupon.model.CouponReservation;
import com.lunchcatch.coupon.model.CouponReservationOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Redis 저장소를 모의 객체로 두고 순번 예약, 반납, 차단 판단만 단위로 검증한다.
class CouponSequenceServiceTest {

    private final CouponSequenceRepository repository = mock(CouponSequenceRepository.class);
    private final CouponSequenceService service = new CouponSequenceService(CouponTestProperties.defaults(), repository);

    // 트랜잭션 동기화를 켜고 Redis 묶음 예약이 순번 1을 돌려주게 고정한다.
    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(service, "properties", CouponTestProperties.with(java.util.Map.of("coupon.sequence.stale-after", Duration.ofSeconds(30))));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(repository.reserveAll(eq(81L), anyList(), anyLong())).thenReturn(List.of(1L));
    }

    // 테스트가 켠 트랜잭션 동기화를 지운다.
    @AfterEach
    void cleanUp() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    // 트랜잭션이 롤백으로 끝나면 받은 순번을 같은 예약 ID로 반납하고 이벤트는 차단하지 않아야 한다.
    @Test
    void DB_롤백이_확정돼야_예약을_반납한다() {
        CouponReservation reservation = reserveOne();
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(repository).release(81L, 1, reservation.id());
        assertThat(service.needsRecovery(81L)).isFalse();
    }

    // 커밋 여부를 모르면 순번을 함부로 돌려놓지 않고 이벤트를 차단해 새 발급을 막아야 한다.
    @Test
    void DB_결과가_불분명하면_예약을_반납하지_않고_새_발급을_차단한다() {
        CouponReservation reservation = reserveOne();
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        verify(repository, never()).release(81L, 1, reservation.id());
        assertThat(service.needsRecovery(81L)).isTrue();
        assertThatThrownBy(() -> service.checkAdmission(81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
    }

    // 커밋된 예약은 반납하지 않고 차단도 하지 않아야 한다.
    @Test
    void DB_커밋이_끝났으면_롤백_반납을_실행하지_않는다() {
        CouponReservation reservation = reserveOne();
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(repository, never()).release(81L, 1, reservation.id());
        assertThat(service.needsRecovery(81L)).isFalse();
    }

    // 롤백 뒤 반납이 Redis 오류로 실패하면 큐 상태를 믿을 수 없으므로 이벤트를 차단해야 한다.
    @Test
    void 반납에_실패하면_후속_발급을_차단한다() {
        CouponReservation reservation = reserveOne();
        doThrow(new RedisConnectionFailureException("release failed")).when(repository)
                .release(81L, 1, reservation.id());
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(service.needsRecovery(81L)).isTrue();
    }

    // Redis 예약 호출이 실패하면 소진으로 착각하지 않고 ISSUANCE_BUSY로 거절하며 이벤트를 차단해야 한다.
    @Test
    void 예약_응답이_유실되면_소진으로_바꾸지_않고_발급을_차단한다() {
        when(repository.reserveAll(eq(81L), anyList(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("reservation reply lost"));
        assertThatThrownBy(() -> service.reserveAll(81L, 1)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
        assertThat(service.needsRecovery(81L)).isTrue();
    }

    /*
     * Redis 결과의 양수, 0, -2가 요청 순서대로 순번, INVENTORY_EXHAUSTED, ISSUANCE_BUSY로 바뀌어야 하고
     * -2만으로는 이벤트를 차단하지 않아야 한다.
     */
    @Test
    void 묶음_예약은_요청_순서대로_순번과_거절_코드를_돌려준다() {
        when(repository.reserveAll(eq(81L), anyList(), anyLong())).thenReturn(List.of(4L, 0L, -2L));
        List<CouponReservationOutcome> reserved = service.reserveAll(81L, 3);
        assertThat(reserved.get(0).reservation().sequence()).isEqualTo(4);
        assertThat(reserved.get(1).rejection()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED);
        assertThat(reserved.get(2).rejection()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY);
        assertThat(service.needsRecovery(81L)).isFalse();
    }

    // Redis가 -1(큐 차단)을 돌려주면 모든 자리를 ISSUANCE_BUSY로 거절하고 이벤트를 차단해야 한다.
    @Test
    void 큐가_차단_상태면_묶음_예약이_이벤트를_차단한다() {
        when(repository.reserveAll(eq(81L), anyList(), anyLong())).thenReturn(List.of(-1L, -1L));
        List<CouponReservationOutcome> reserved = service.reserveAll(81L, 2);
        assertThat(reserved).allSatisfy(item -> assertThat(item.rejection()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
        assertThat(service.needsRecovery(81L)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"-1, false", "0, true", "1, false"})
    void 사전_검사는_준비된_큐의_확정_소진만_반환한다(int availability, boolean soldOut) {
        when(repository.availability(81L)).thenReturn(availability);

        assertThat(service.isConfirmedSoldOut(81L)).isEqualTo(soldOut);
        assertThat(service.needsRecovery(81L)).isFalse();
    }

    @Test
    void 사전_검사의_Redis_조회_장애는_소진으로_바꾸지_않고_복구를_요청한다() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        when(repository.availability(81L)).thenThrow(new RedisConnectionFailureException("availability failed"));

        assertThatThrownBy(() -> service.isConfirmedSoldOut(81L)).isInstanceOfSatisfying(CouponException.class,
                error -> {
                    assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY);
                    assertThat(error.canResumeIssuance(81L)).isTrue();
                });
        assertThat(service.needsRecovery(81L)).isTrue();
    }

    @Test
    void 로컬에서_차단된_이벤트는_사전_검사에서도_Redis를_다시_읽지_않는다() {
        service.block(81L, null);

        assertThatThrownBy(() -> service.isConfirmedSoldOut(81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
        verify(repository, never()).availability(81L);
    }

    // 소진 확인에서 큐가 아직 준비되지 않았으면 소진이 아니라 차단으로 처리해 다음 요청이 복구하게 해야 한다.
    @Test
    void 큐가_준비되지_않았으면_소진_확인이_이벤트를_차단한다() {
        when(repository.availability(81L)).thenReturn(-1);
        assertThatThrownBy(() -> service.isSoldOut(81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
        assertThat(service.needsRecovery(81L)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0, false", "1, true", "2, false"})
    void 순번_오류는_롤백이_확정된_경우에만_복구_후_재접수를_허용한다(int status, boolean allowed) {
        when(repository.availability(81L)).thenReturn(-1);
        CouponException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.isSoldOut(81L), CouponException.class);
        assertThat(failure.canResumeIssuance(81L)).isFalse();

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));

        assertThat(failure.canResumeIssuance(81L)).isEqualTo(allowed);
        assertThat(failure.canResumeIssuance(82L)).isFalse();
    }

    @Test
    void 여러_이벤트의_예약은_이벤트별_요청_순서와_대기_여부를_유지한다() {
        when(repository.reserveAll(org.mockito.ArgumentMatchers.anyMap(), eq(30_000L)))
                .thenReturn(Map.of(81L, List.of(4L, -2L), 82L, List.of(0L)));

        Map<Long, List<CouponReservationOutcome>> reserved = service.reserveAll(Map.of(81L, 2, 82L, 1));

        CouponReservation taken = reserved.get(81L).getFirst().reservation();
        assertThat(taken.eventId()).isEqualTo(81L);
        assertThat(taken.sequence()).isEqualTo(4);
        assertThat(reserved.get(81L).get(1).awaitingReservation()).isTrue();
        assertThat(reserved.get(81L).get(1).recoveryRequired()).isFalse();
        assertThat(reserved.get(82L).getFirst().rejection()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(repository).release(81L, 4, taken.id());
    }

    @Test
    void 여러_이벤트의_예약_응답을_잃으면_부분_실행된_이벤트도_모두_차단한다() {
        when(repository.reserveAll(org.mockito.ArgumentMatchers.anyMap(), eq(30_000L)))
                .thenThrow(new RedisConnectionFailureException("pipeline reply lost"));

        assertThatThrownBy(() -> service.reserveAll(Map.of(81L, 1, 82L, 1)))
                .isInstanceOfSatisfying(CouponException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));

        assertThat(service.needsRecovery(81L)).isTrue();
        assertThat(service.needsRecovery(82L)).isTrue();
        verify(repository).blockAll(Set.of(81L, 82L));
    }

    // 이 서버가 차단한 이벤트는 Redis를 부르지 않고 그 이벤트의 요청만 복구 대상으로 돌려줘야 한다.
    @Test
    void 차단한_이벤트의_예약은_Redis를_부르지_않고_그_이벤트만_복구_대상으로_돌려준다() {
        service.block(81L, null);
        when(repository.reserveAll(org.mockito.ArgumentMatchers.anyMap(), eq(30_000L)))
                .thenReturn(Map.of(82L, List.of(5L)));

        Map<Long, List<CouponReservationOutcome>> reserved = service.reserveAll(Map.of(81L, 2, 82L, 1));

        assertThat(reserved.get(81L)).hasSize(2).allSatisfy(outcome -> {
            assertThat(outcome.reservation()).isNull();
            assertThat(outcome.recoveryRequired()).isTrue();
        });
        assertThat(reserved.get(82L).getFirst().reservation().sequence()).isEqualTo(5);
        verify(repository).reserveAll(org.mockito.ArgumentMatchers.<Map<Long, List<String>>>argThat(
                ids -> ids.keySet().equals(Set.of(82L))), eq(30_000L));
    }

    // 확정 소진인 이벤트만 소진 결과를 받고 재고가 남은 이벤트는 결과 없이 계속 발급해야 한다.
    @Test
    void 여러_이벤트를_조회하면_확정_소진만_소진으로_돌려준다() {
        when(repository.availability(anyList())).thenReturn(Map.of(81L, 0, 82L, 1));

        Map<Long, CouponIssuanceOutcome> unavailable = service.findUnavailableEvents(Set.of(81L, 82L));

        assertThat(unavailable).containsOnlyKeys(81L);
        assertThat(unavailable.get(81L).rejection()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED);
        assertThat(unavailable.get(81L).recoveryRequired()).isFalse();
        assertThat(service.needsRecovery(82L)).isFalse();
    }

    // 큐가 준비되지 않은 이벤트가 여럿이면 모두 차단하고 그 이벤트만 복구 대상으로 돌려줘야 한다.
    @Test
    void 큐가_준비되지_않은_이벤트를_모두_차단하고_정상_이벤트는_계속_발급한다() {
        when(repository.availability(anyList())).thenReturn(Map.of(81L, -1, 82L, 1, 83L, -1));

        Map<Long, CouponIssuanceOutcome> unavailable = service.findUnavailableEvents(Set.of(81L, 82L, 83L));

        assertThat(unavailable).containsOnlyKeys(81L, 83L);
        assertThat(unavailable.values()).allSatisfy(outcome -> {
            assertThat(outcome.recoveryRequired()).isTrue();
            assertThat(outcome.rejection()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY);
        });
        assertThat(service.needsRecovery(81L)).isTrue();
        assertThat(service.needsRecovery(82L)).isFalse();
        assertThat(service.needsRecovery(83L)).isTrue();
        verify(repository).blockAll(Set.of(81L, 83L));
    }

    // 이 서버가 이미 차단한 이벤트는 Redis를 조회하지 않고 복구 대상으로 돌려줘야 한다.
    @Test
    void 이미_차단한_이벤트는_Redis를_조회하지_않고_복구_대상으로_돌려준다() {
        service.block(81L, null);
        when(repository.availability(List.of(82L))).thenReturn(Map.of(82L, 1));

        Map<Long, CouponIssuanceOutcome> unavailable = service.findUnavailableEvents(Set.of(81L, 82L));

        assertThat(unavailable).containsOnlyKeys(81L);
        assertThat(unavailable.get(81L).recoveryRequired()).isTrue();
        verify(repository).availability(List.of(82L));
    }

    // Redis 조회 자체가 실패하면 기존처럼 묶음의 모든 이벤트를 차단하고 실패시켜야 한다.
    @Test
    void 여러_이벤트의_소진_조회가_실패하면_모든_이벤트를_복구_대상으로_남긴다() {
        when(repository.availability(anyList())).thenThrow(new RedisConnectionFailureException("pipeline failed"));

        assertThatThrownBy(() -> service.findUnavailableEvents(Set.of(81L, 82L))).isInstanceOf(CouponException.class);

        assertThat(service.needsRecovery(81L)).isTrue();
        assertThat(service.needsRecovery(82L)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void 예약_대기는_소진과_반납된_재고와_미완료_예약을_구분한다(int availability) {
        when(repository.retryAvailability(81L, 30_000L)).thenReturn(availability);

        assertThat(service.retryAvailability(81L)).isEqualTo(availability);
        assertThat(service.needsRecovery(81L)).isFalse();
    }

    @Test
    void 예약_대기_중_큐가_차단되면_복구를_요청한다() {
        when(repository.retryAvailability(81L, 30_000L)).thenReturn(-1);

        assertThatThrownBy(() -> service.retryAvailability(81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
        assertThat(service.needsRecovery(81L)).isTrue();
    }

    @Test
    void 예약_완료는_저장소가_확인한_소진_여부를_돌려준다() {
        CouponReservation reservation = new CouponReservation(81L, 1, "first");
        when(repository.complete(81L, 1, "first")).thenReturn(true);

        assertThat(service.complete(reservation)).isTrue();
        assertThat(service.needsRecovery(81L)).isFalse();
    }

    @Test
    void 묶음_예약을_완료하면_소진된_이벤트만_돌려준다() {
        when(repository.completeAll(81L, List.of(1, 2), List.of("first", "second"))).thenReturn(true);
        List<CouponReservation> reservations = List.of(new CouponReservation(81L, 1, "first"),
                new CouponReservation(81L, 2, "second"), new CouponReservation(82L, 1, "third"));

        assertThat(service.completeAll(reservations)).containsExactly(81L);
        assertThat(service.needsRecovery(82L)).isFalse();
    }

    @Test
    void 한_이벤트의_완료가_실패해도_다른_이벤트의_소진_결과를_유지한다() {
        when(repository.completeAll(81L, List.of(1), List.of("first")))
                .thenThrow(new RedisConnectionFailureException("complete failed"));
        when(repository.completeAll(82L, List.of(1), List.of("second"))).thenReturn(true);

        assertThat(service.completeAll(List.of(new CouponReservation(81L, 1, "first"),
                new CouponReservation(82L, 1, "second")))).containsExactly(82L);
        assertThat(service.needsRecovery(81L)).isTrue();
        assertThat(service.needsRecovery(82L)).isFalse();
    }

    @Test
    void 복구가_완료되면_로컬_차단을_풀고_새_요청을_받는다() {
        service.block(81L, null);

        service.resume(81L);

        assertThat(service.needsRecovery(81L)).isFalse();
        org.assertj.core.api.Assertions.assertThatCode(() -> service.checkAdmission(81L)).doesNotThrowAnyException();
    }

    @Test
    void Redis_차단_실패는_예외_메시지_없이_분류한_사유를_남긴다() {
        doThrow(new org.springframework.dao.QueryTimeoutException("secret-token")).when(repository).block(81L);
        try (var logs = new CouponLogCapture(CouponSequenceService.class)) {
            service.block(81L, null);

            assertThat(logs.events).filteredOn(event -> event.getFormattedMessage().contains("COUPON_REDIS_BLOCK_FAILED"))
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("exceptionType=QueryTimeoutException", "reason=TIMEOUT")
                                .doesNotContain("secret-token");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
        }
    }

    // 순번 하나를 예약하고 그 예약을 돌려준다.
    private CouponReservation reserveOne() {
        return service.reserveAll(81L, 1).getFirst().reservation();
    }
}
