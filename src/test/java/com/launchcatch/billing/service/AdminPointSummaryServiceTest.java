package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.AdminPointSummaryItem;
import com.launchcatch.billing.dto.AdminPointSummaryResponse;
import com.launchcatch.billing.dto.SummaryPeriod;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.BusinessDateSumRow;
import com.launchcatch.billing.repository.FlowRow;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPointSummaryServiceTest {

    private static final Long OWNER = 7L;
    private static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate D01 = LocalDate.of(2026, 10, 1);

    @Mock PointLedgerRepository repository;

    private AdminPointSummaryService service() {
        return new AdminPointSummaryService(repository);
    }

    private void threeDaysOfLedger() {
        when(repository.findFlowRows(OWNER, LocalDateTime.of(2026, 9, 29, 0, 0), LocalDateTime.of(2026, 10, 2, 0, 0)))
                .thenReturn(List.of(
                        new FlowRow(EntryType.CHARGE, LocalDateTime.of(2026, 9, 29, 10, 0), 30000L, null),
                        new FlowRow(EntryType.REFUND, LocalDateTime.of(2026, 9, 30, 11, 0), -5000L, null),
                        new FlowRow(EntryType.ADJUST, LocalDateTime.of(2026, 9, 30, 15, 0), 1000L, null),
                        new FlowRow(EntryType.ADJUST, LocalDateTime.of(2026, 10, 1, 9, 0), -300L, null)));
        when(repository.sumReserveAndReleaseByBusinessDate(OWNER, D29, D01)).thenReturn(List.of(
                new BusinessDateSumRow(D29, EntryType.RESERVE, -10000L),
                new BusinessDateSumRow(D29, EntryType.RELEASE, 200L),
                new BusinessDateSumRow(D30, EntryType.RESERVE, -10000L),
                new BusinessDateSumRow(D30, EntryType.RELEASE, 200L),
                new BusinessDateSumRow(D01, EntryType.RESERVE, -10000L)));
        when(repository.sumDeductByServedBusinessDate(OWNER, D29, D01)).thenReturn(List.of(
                new BusinessDateSumRow(D29, EntryType.DEDUCT, -9800L),
                new BusinessDateSumRow(D30, EntryType.DEDUCT, -9800L),
                new BusinessDateSumRow(D01, EntryType.DEDUCT, -4000L)));
    }

    @Test
    void 날짜별_소진은_서빙_영업일의_DEDUCT_합이다() {
        threeDaysOfLedger();

        AdminPointSummaryResponse response = service().summarize(OWNER, SummaryPeriod.DAILY, D29, D01, null);

        assertThat(response.items()).containsExactly(
                new AdminPointSummaryItem("2026-09-29", 30000, 9800, 10000, 200, 0, 0),
                // 소진 9800 에서 양수 조정 1000 을 뺀다. 환불은 크기로 내린다
                new AdminPointSummaryItem("2026-09-30", 0, 8800, 10000, 200, 5000, 1000),
                // 음수 조정은 소진에서 빼지 않고 조정액에만 들어간다
                new AdminPointSummaryItem("2026-10-01", 0, 4000, 10000, 0, 0, -300));
        assertThat(response.ownerId()).isEqualTo(OWNER);
        assertThat(response.period()).isEqualTo(SummaryPeriod.DAILY);
        assertThat(response.from()).isEqualTo(D29);
        assertThat(response.to()).isEqualTo(D01);
    }

    @Test
    void 내림차순이면_구간_순서를_뒤집는다() {
        threeDaysOfLedger();

        List<AdminPointSummaryItem> items =
                service().summarize(OWNER, SummaryPeriod.DAILY, D29, D01, "DESC").items();

        assertThat(items).extracting(AdminPointSummaryItem::label)
                .containsExactly("2026-10-01", "2026-09-30", "2026-09-29");
    }

    @Test
    void 소진은_기록일이_아니라_서빙_영업일의_DEDUCT로_센다() {
        when(repository.sumReserveAndReleaseByBusinessDate(OWNER, D01, D01)).thenReturn(List.of(
                new BusinessDateSumRow(D01, EntryType.RESERVE, -10000L),
                new BusinessDateSumRow(D01, EntryType.RELEASE, 3500L)));
        when(repository.sumDeductByServedBusinessDate(OWNER, D01, D01))
                .thenReturn(List.of(new BusinessDateSumRow(D01, EntryType.DEDUCT, -6500L)));

        AdminPointSummaryItem item = service().summarize(OWNER, SummaryPeriod.DAILY, D01, D01, "asc").items().get(0);

        assertThat(item.spentAmount()).isEqualTo(6500);
    }

    @Test
    void 예약과_해제가_없는_날도_서빙_영업일의_DEDUCT가_있으면_소진으로_보인다() {
        when(repository.sumDeductByServedBusinessDate(OWNER, D30, D30))
                .thenReturn(List.of(new BusinessDateSumRow(D30, EntryType.DEDUCT, -700L)));

        AdminPointSummaryItem item = service().summarize(OWNER, SummaryPeriod.DAILY, D30, D30, null).items().get(0);

        assertThat(item.spentAmount()).isEqualTo(700);
        assertThat(item.reservedAmount()).isZero();
    }

    @Test
    void 예약만_있고_해제가_없는_지난_날은_예약_전부가_소진으로_보인다() {
        when(repository.sumReserveAndReleaseByBusinessDate(OWNER, D30, D30))
                .thenReturn(List.of(new BusinessDateSumRow(D30, EntryType.RESERVE, -10000L)));

        AdminPointSummaryItem item = service().summarize(OWNER, SummaryPeriod.DAILY, D30, D30, null).items().get(0);

        assertThat(item.spentAmount()).isZero();
        assertThat(item.releasedAmount()).isZero();
    }

    @Test
    void 변동이_없는_구간은_금액이_모두_0이다() {
        List<AdminPointSummaryItem> items =
                service().summarize(OWNER, SummaryPeriod.DAILY, D29, D30, null).items();

        assertThat(items).containsExactly(
                new AdminPointSummaryItem("2026-09-29", 0, 0, 0, 0, 0, 0),
                new AdminPointSummaryItem("2026-09-30", 0, 0, 0, 0, 0, 0));
    }

    @Test
    void 정정은_기록한_날이_아니라_정정_대상_영업일의_구간에_들어간다() {
        when(repository.findFlowRows(OWNER, LocalDateTime.of(2026, 9, 29, 0, 0), LocalDateTime.of(2026, 10, 2, 0, 0)))
                .thenReturn(List.of(
                        // 10-03 에 기록했지만 09-30 의 과금을 고친 정정이다
                        new FlowRow(EntryType.ADJUST, LocalDateTime.of(2026, 10, 3, 9, 0), 1000L, D30),
                        // 정정 대상이 없는 조정은 기록한 날의 구간에 들어간다
                        new FlowRow(EntryType.ADJUST, LocalDateTime.of(2026, 10, 1, 9, 0), -300L, null)));
        when(repository.sumReserveAndReleaseByBusinessDate(OWNER, D29, D01)).thenReturn(List.of());

        List<AdminPointSummaryItem> items =
                service().summarize(OWNER, SummaryPeriod.DAILY, D29, D01, null).items();

        assertThat(items).containsExactly(
                new AdminPointSummaryItem("2026-09-29", 0, 0, 0, 0, 0, 0),
                new AdminPointSummaryItem("2026-09-30", 0, -1000, 0, 0, 0, 1000),
                new AdminPointSummaryItem("2026-10-01", 0, 0, 0, 0, 0, -300));
    }

    @Test
    void 주별은_걸친_주의_날짜만_합산하고_월요일을_라벨로_쓴다() {
        // 09-30 은 수요일이라 첫 주는 09-30 부터 10-04 까지로 잘린다
        LocalDate to = LocalDate.of(2026, 10, 6);
        when(repository.findFlowRows(OWNER, LocalDateTime.of(2026, 9, 30, 0, 0), LocalDateTime.of(2026, 10, 7, 0, 0)))
                .thenReturn(List.of(
                        new FlowRow(EntryType.CHARGE, LocalDateTime.of(2026, 10, 4, 23, 59), 10000L, null),
                        new FlowRow(EntryType.CHARGE, LocalDateTime.of(2026, 10, 5, 0, 0), 20000L, null)));

        List<AdminPointSummaryItem> items =
                service().summarize(OWNER, SummaryPeriod.WEEKLY, D30, to, null).items();

        assertThat(items).extracting(AdminPointSummaryItem::label).containsExactly("2026-09-28", "2026-10-05");
        assertThat(items).extracting(AdminPointSummaryItem::chargeAmount).containsExactly(10000L, 20000L);
    }

    @Test
    void 점주_ID나_단위_정렬_방향이_올바르지_않으면_질의하지_않고_거절한다() {
        assertThatThrownBy(() -> service().summarize(OWNER, null, D29, D30, null))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> service().summarize(OWNER, SummaryPeriod.DAILY, D29, D30, "up"))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service().summarize(null, SummaryPeriod.DAILY, D29, D30, null))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verify(repository, never()).findFlowRows(any(), any(), any());
    }

    @Test
    void 기간이_없거나_뒤집혔거나_단위별_상한을_넘으면_거절한다() {
        assertThatThrownBy(() -> service().summarize(OWNER, SummaryPeriod.DAILY, null, D30, null))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service().summarize(OWNER, SummaryPeriod.DAILY, D30, D29, null))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service().summarize(OWNER, SummaryPeriod.DAILY, D29, D29.plusDays(92), null))
                .isInstanceOf(BillingException.class);
    }
}
