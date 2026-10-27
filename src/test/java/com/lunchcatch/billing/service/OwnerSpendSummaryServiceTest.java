package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.SpendGroupBy;
import com.lunchcatch.billing.dto.SpendSummaryItem;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.DeductSumRow;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.ReserveSumRow;
import com.lunchcatch.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerSpendSummaryServiceTest {

    private static final Long OWNER = 7L;
    private static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);

    @Mock PointLedgerRepository repository;
    @InjectMocks OwnerSpendSummaryService service;

    private void ledgerOfTwoDays() {
        when(repository.sumDeductedByCampaign(eq(OWNER), eq(LocalDateTime.of(2026, 9, 29, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 30, 0, 0)), isNull()))
                .thenReturn(List.of(new DeductSumRow(12L, -9800L, 980L), new DeductSumRow(13L, -200L, 20L)));
        when(repository.sumDeductedByCampaign(eq(OWNER), eq(LocalDateTime.of(2026, 9, 30, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 1, 0, 0)), isNull()))
                .thenReturn(List.of(new DeductSumRow(12L, -5000L, 500L)));
        when(repository.sumReservedByBusinessDate(OWNER, D29, D30, null))
                .thenReturn(List.of(new ReserveSumRow(D29, 12L, -10000L), new ReserveSumRow(D29, 13L, -1000L),
                        new ReserveSumRow(D30, 12L, -10000L)));
    }

    @Test
    void 날짜와_캠페인_조합별로_묶는다() {
        ledgerOfTwoDays();

        List<SpendSummaryItem> items =
                service.summarize(OWNER, SpendGroupBy.DAY_CAMPAIGN, D29, D30, null).items();

        assertThat(items).containsExactly(
                new SpendSummaryItem(D29, 12L, 9800, 10000, 980),
                new SpendSummaryItem(D29, 13L, 200, 1000, 20),
                new SpendSummaryItem(D30, 12L, 5000, 10000, 500));
    }

    @Test
    void 날짜별로_묶으면_캠페인을_합치고_캠페인_ID를_비운다() {
        ledgerOfTwoDays();

        List<SpendSummaryItem> items = service.summarize(OWNER, SpendGroupBy.DAY, D29, D30, null).items();

        assertThat(items).containsExactly(
                new SpendSummaryItem(D29, null, 10000, 11000, 1000),
                new SpendSummaryItem(D30, null, 5000, 10000, 500));
    }

    @Test
    void 캠페인별로_묶으면_기간을_합치고_날짜를_비운다() {
        ledgerOfTwoDays();

        List<SpendSummaryItem> items = service.summarize(OWNER, SpendGroupBy.CAMPAIGN, D29, D30, null).items();

        assertThat(items).containsExactly(
                new SpendSummaryItem(null, 12L, 14800, 20000, 1480),
                new SpendSummaryItem(null, 13L, 200, 1000, 20));
    }

    @Test
    void 예약만_있고_차감이_없는_캠페인은_소진이_0이다() {
        when(repository.sumReservedByBusinessDate(OWNER, D30, D30, null))
                .thenReturn(List.of(new ReserveSumRow(D30, 12L, -10000L)));

        List<SpendSummaryItem> items =
                service.summarize(OWNER, SpendGroupBy.DAY_CAMPAIGN, D30, D30, null).items();

        assertThat(items).containsExactly(new SpendSummaryItem(D30, 12L, 0, 10000, 0));
    }

    @Test
    void 기록이_없으면_빈_목록이다() {
        assertThat(service.summarize(OWNER, SpendGroupBy.DAY, D30, D30, null).items()).isEmpty();
    }

    @Test
    void 캠페인_필터를_저장소에_넘긴다() {
        service.summarize(OWNER, SpendGroupBy.DAY, D30, D30, 12L);

        verify(repository).sumDeductedByCampaign(OWNER, LocalDateTime.of(2026, 9, 30, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0), 12L);
        verify(repository).sumReservedByBusinessDate(OWNER, D30, D30, 12L);
    }

    @Test
    void 기간은_31일까지만_허용한다() {
        LocalDate from = LocalDate.of(2026, 9, 1);

        assertThat(service.summarize(OWNER, SpendGroupBy.DAY, from, from.plusDays(30), null).items()).isEmpty();
        assertThatThrownBy(() -> service.summarize(OWNER, SpendGroupBy.DAY, from, from.plusDays(31), null))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 기간이_없거나_뒤집히면_질의하지_않고_거절한다() {
        assertThatThrownBy(() -> service.summarize(OWNER, SpendGroupBy.DAY, null, D30, null))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service.summarize(OWNER, SpendGroupBy.DAY, D30, D29, null))
                .isInstanceOf(BillingException.class);

        verify(repository, never()).sumDeductedByCampaign(any(), any(), any(), any());
    }
}
