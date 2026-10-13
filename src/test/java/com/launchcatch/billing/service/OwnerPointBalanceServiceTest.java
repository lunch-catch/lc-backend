package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.PointBalanceResponse;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(MockitoExtension.class)
class OwnerPointBalanceServiceTest {

    private static final Long OWNER = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime DAY_END = LocalDateTime.of(2026, 10, 2, 0, 0);

    @Mock PointLedgerRepository repository;

    // 2026-10-01 12:00 KST
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), ClockConfig.ZONE);

    private OwnerPointBalanceService service() {
        return new OwnerPointBalanceService(repository, clock);
    }

    private void today(long reserve, long release, long deduct) {
        when(repository.sumByBusinessDate(OWNER, EntryType.RESERVE, TODAY)).thenReturn(reserve);
        when(repository.sumByBusinessDate(OWNER, EntryType.RELEASE, TODAY)).thenReturn(release);
        when(repository.sumByCreatedAt(OWNER, EntryType.DEDUCT, DAY_START, DAY_END)).thenReturn(deduct);
    }

    @Test
    void 예약_중인_포인트는_예약에서_차감과_해제를_뺀_크기다() {
        when(repository.sumBalance(OWNER)).thenReturn(12000L);
        today(-10000, 0, -6500);

        PointBalanceResponse response = service().getBalance(OWNER);

        assertThat(response.balance()).isEqualTo(12000);
        assertThat(response.reservedPoint()).isEqualTo(3500);
        assertThat(response.unspentTotal()).isEqualTo(15500);
        assertThat(response.businessDate()).isEqualTo(TODAY);
    }

    @Test
    void 해제가_끝나면_예약_중인_포인트는_0이고_잔액과_겹치지_않는다() {
        when(repository.sumBalance(OWNER)).thenReturn(15500L);
        today(-10000, 3500, -6500);

        PointBalanceResponse response = service().getBalance(OWNER);

        assertThat(response.reservedPoint()).isZero();
        assertThat(response.unspentTotal()).isEqualTo(15500);
    }

    @Test
    void 계산이_음수가_되어도_예약_중인_포인트는_0_미만으로_내리지_않는다() {
        when(repository.sumBalance(OWNER)).thenReturn(0L);
        today(-1000, 0, -1500);

        assertThat(service().getBalance(OWNER).reservedPoint()).isZero();
    }

    @Test
    void 원장을_읽지_못하면_캐시_없이_BILLING_006으로_실패한다() {
        when(repository.sumBalance(OWNER)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service().getBalance(OWNER))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.LEDGER_UNAVAILABLE));
    }

    @Test
    void 커넥션을_얻지_못한_실패도_BILLING_006으로_옮긴다() {
        when(repository.sumBalance(OWNER)).thenThrow(new CannotCreateTransactionException("no connection"));

        assertThatThrownBy(() -> service().getBalance(OWNER))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.LEDGER_UNAVAILABLE));
    }
}
