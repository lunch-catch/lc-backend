package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.AdminPaymentItem;
import com.lunchcatch.billing.entity.OutboxStatus;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.AdminPaymentCondition;
import com.lunchcatch.billing.repository.AdminPaymentCondition.Sort;
import com.lunchcatch.billing.repository.AdminPaymentRow;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPaymentQueryServiceTest {

    private static final LocalDateTime REQUESTED = LocalDateTime.of(2026, 10, 1, 10, 30);

    @Mock PaymentRepository paymentRepository;

    private AdminPaymentQueryService service() {
        return new AdminPaymentQueryService(paymentRepository);
    }

    private PageResponse<AdminPaymentItem> search(LocalDate from, LocalDate to, PaymentStatus status,
                                                  String searchType, String keyword, int page, int size,
                                                  String sortBy, String sortDir) {
        return service().search(from, to, status, searchType, keyword, page, size, sortBy, sortDir);
    }

    private PageResponse<AdminPaymentItem> searchDefaults() {
        return search(null, null, null, null, null, 0, 20, null, null);
    }

    private AdminPaymentCondition capturedCondition() {
        ArgumentCaptor<AdminPaymentCondition> captor = ArgumentCaptor.forClass(AdminPaymentCondition.class);
        verify(paymentRepository).countAdminPayments(captor.capture());
        return captor.getValue();
    }

    private void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BillingException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verify(paymentRepository, never()).countAdminPayments(any());
    }

    @Test
    void 조건이_없으면_요청_시각_내림차순_첫_페이지다() {
        searchDefaults();

        AdminPaymentCondition condition = capturedCondition();
        assertThat(condition).isEqualTo(new AdminPaymentCondition(
                null, null, null, null, null, null, Sort.REQUESTED_AT, false, 0, 20));
    }

    @Test
    void 기간은_요청_시각의_KST_날짜이고_양_끝을_포함한다() {
        search(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), null, null, null, 0, 20, null, null);

        AdminPaymentCondition condition = capturedCondition();
        assertThat(condition.fromInclusive()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(condition.toExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 4, 0, 0));
    }

    @Test
    void 기간은_한쪽만_줘도_된다() {
        search(LocalDate.of(2026, 10, 1), null, null, null, null, 0, 20, null, null);

        assertThat(capturedCondition().fromInclusive()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(capturedCondition().toExclusive()).isNull();
    }

    @Test
    void 기간이_뒤집히면_400이다() {
        assertInvalid(() -> search(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1), null, null, null, 0, 20,
                null, null));
    }

    @Test
    void 상태는_그대로_조건에_담는다() {
        search(null, null, PaymentStatus.UNKNOWN, null, null, 0, 20, null, null);

        assertThat(capturedCondition().status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void 검색_종류별로_정확_일치_조건이_하나만_걸린다() {
        search(null, null, null, "OWNER_ID", " 7 ", 0, 20, null, null);
        search(null, null, null, "ORDER_ID", "ord_1", 0, 20, null, null);
        search(null, null, null, "PG_TID", "tgen_1", 0, 20, null, null);

        ArgumentCaptor<AdminPaymentCondition> captor = ArgumentCaptor.forClass(AdminPaymentCondition.class);
        verify(paymentRepository, times(3)).countAdminPayments(captor.capture());
        List<AdminPaymentCondition> conditions = captor.getAllValues();
        assertThat(conditions.get(0).ownerId()).isEqualTo(7L);
        assertThat(conditions.get(0).orderId()).isNull();
        assertThat(conditions.get(1).orderId()).isEqualTo("ord_1");
        assertThat(conditions.get(1).ownerId()).isNull();
        assertThat(conditions.get(2).pgTid()).isEqualTo("tgen_1");
        assertThat(conditions.get(2).orderId()).isNull();
    }

    @Test
    void 검색어_없이_검색_종류만_주면_조건을_걸지_않는다() {
        search(null, null, null, "OWNER_ID", " ", 0, 20, null, null);

        assertThat(capturedCondition().ownerId()).isNull();
    }

    @Test
    void 검색어만_있고_검색_종류가_없으면_400이다() {
        assertInvalid(() -> search(null, null, null, null, "7", 0, 20, null, null));
        assertInvalid(() -> search(null, null, null, " ", "7", 0, 20, null, null));
    }

    @Test
    void 모르는_검색_종류는_400이다() {
        assertInvalid(() -> search(null, null, null, "NAME", "7", 0, 20, null, null));
    }

    @Test
    void 점주_ID_검색어가_숫자가_아니면_400이다() {
        assertInvalid(() -> search(null, null, null, "OWNER_ID", "abc", 0, 20, null, null));
    }

    @Test
    void 정렬_기준과_방향을_조건에_옮긴다() {
        search(null, null, null, null, null, 0, 20, "amount", "asc");
        search(null, null, null, null, null, 0, 20, "approvedAt", "DESC");
        search(null, null, null, null, null, 0, 20, "requestedAt", null);

        ArgumentCaptor<AdminPaymentCondition> captor = ArgumentCaptor.forClass(AdminPaymentCondition.class);
        verify(paymentRepository, times(3)).countAdminPayments(captor.capture());
        List<AdminPaymentCondition> conditions = captor.getAllValues();
        assertThat(conditions.get(0).sort()).isEqualTo(Sort.AMOUNT);
        assertThat(conditions.get(0).ascending()).isTrue();
        assertThat(conditions.get(1).sort()).isEqualTo(Sort.APPROVED_AT);
        assertThat(conditions.get(1).ascending()).isFalse();
        assertThat(conditions.get(2).sort()).isEqualTo(Sort.REQUESTED_AT);
    }

    @Test
    void 허용하지_않는_정렬_기준이나_방향은_400이다() {
        assertInvalid(() -> search(null, null, null, null, null, 0, 20, "ownerId", null));
        assertInvalid(() -> search(null, null, null, null, null, 0, 20, null, "up"));
    }

    @Test
    void 쪽_번호가_음수면_400이다() {
        assertInvalid(() -> search(null, null, null, null, null, -1, 20, null, null));
    }

    @Test
    void 쪽_크기는_기본_20이고_최대_100으로_보정하며_건너뛸_수는_쪽_번호로_센다() {
        search(null, null, null, null, null, 2, 0, null, null);
        search(null, null, null, null, null, 1, 500, null, null);

        ArgumentCaptor<AdminPaymentCondition> captor = ArgumentCaptor.forClass(AdminPaymentCondition.class);
        verify(paymentRepository, times(2)).countAdminPayments(captor.capture());
        assertThat(captor.getAllValues().get(0).limit()).isEqualTo(20);
        assertThat(captor.getAllValues().get(0).offset()).isEqualTo(40);
        assertThat(captor.getAllValues().get(1).limit()).isEqualTo(100);
        assertThat(captor.getAllValues().get(1).offset()).isEqualTo(100);
    }

    @Test
    void 건수가_0이면_목록을_읽지_않고_빈_쪽을_돌려준다() {
        when(paymentRepository.countAdminPayments(any())).thenReturn(0L);

        PageResponse<AdminPaymentItem> result = searchDefaults();

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verify(paymentRepository, never()).findAdminPayments(any());
    }

    @Test
    void 재조회_아웃박스가_있으면_함께_내리고_시각은_KST다() {
        when(paymentRepository.countAdminPayments(any())).thenReturn(1L);
        when(paymentRepository.findAdminPayments(any())).thenReturn(List.of(new AdminPaymentRow(
                101L, "ord_1", "tgen_1", 7L, 30000L, PaymentStatus.UNKNOWN, null, REQUESTED, null, null,
                OutboxStatus.PENDING, 2, 5, REQUESTED.plusMinutes(4), "PG timeout")));

        PageResponse<AdminPaymentItem> result = searchDefaults();

        assertThat(result.totalElements()).isEqualTo(1);
        AdminPaymentItem item = result.items().get(0);
        assertThat(item.paymentId()).isEqualTo(101L);
        assertThat(item.chargedPoint()).isEqualTo(30000L);
        assertThat(item.requestedAt().toString()).isEqualTo("2026-10-01T10:30+09:00");
        assertThat(item.approvedAt()).isNull();
        assertThat(item.reconciliation().status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(item.reconciliation().attemptCount()).isEqualTo(2);
        assertThat(item.reconciliation().maxRetry()).isEqualTo(5);
        assertThat(item.reconciliation().nextRetryAt().toString()).isEqualTo("2026-10-01T10:34+09:00");
        assertThat(item.reconciliation().lastError()).isEqualTo("PG timeout");
    }

    @Test
    void 아웃박스가_없으면_reconciliation은_비어_있다() {
        when(paymentRepository.countAdminPayments(any())).thenReturn(1L);
        when(paymentRepository.findAdminPayments(any())).thenReturn(List.of(new AdminPaymentRow(
                101L, "ord_1", "tgen_1", 7L, 30000L, PaymentStatus.APPROVED, null, REQUESTED, REQUESTED, null,
                null, null, null, null, null)));

        assertThat(searchDefaults().items().get(0).reconciliation()).isNull();
    }

    @Test
    void 결제_한_건은_목록과_같은_모양으로_돌려준다() {
        when(paymentRepository.findAdminPaymentById(101L)).thenReturn(Optional.of(new AdminPaymentRow(
                101L, "ord_1", "tgen_1", 7L, 30000L, PaymentStatus.APPROVED, null, REQUESTED, REQUESTED, null,
                null, null, null, null, null)));

        assertThat(service().get(101L).paymentId()).isEqualTo(101L);
    }

    @Test
    void 없는_결제_한_건은_BILLING_004다() {
        when(paymentRepository.findAdminPaymentById(101L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(101L)).isInstanceOfSatisfying(BillingException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_NOT_FOUND));
    }
}
