package com.launchcatch.coupon.service;

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

import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponSequenceRepository;
import com.launchcatch.coupon.service.model.CouponReservation;
import com.launchcatch.coupon.service.model.CouponReservationOutcome;
import java.time.Duration;
import java.util.List;
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
    private final CouponSequenceService service = new CouponSequenceService(repository);

    // 트랜잭션 동기화를 켜고 Redis 묶음 예약이 순번 1을 돌려주게 고정한다.
    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(service, "staleAfter", Duration.ofSeconds(30));
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

    // 순번 하나를 예약하고 그 예약을 돌려준다.
    private CouponReservation reserveOne() {
        return service.reserveAll(81L, 1).getFirst().reservation();
    }
}
