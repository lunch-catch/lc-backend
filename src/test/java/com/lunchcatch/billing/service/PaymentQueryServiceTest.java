package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.DisplayStatus;
import com.lunchcatch.billing.dto.PaymentDetailResponse;
import com.lunchcatch.billing.dto.PaymentHistoryItem;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.PageCursor;
import com.lunchcatch.global.response.PageTokens;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

    private static final Long OWNER = 7L;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 10, 1, 10, 30);

    @Mock PaymentRepository repository;

    private PaymentQueryService service() {
        return new PaymentQueryService(repository);
    }

    private Payment payment(long id, LocalDateTime createdAt) {
        Payment payment = Payment.order(OWNER, "ord_" + id, 30000);
        payment.assignPgTid("tgen_" + id);
        payment.markUnknown();
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "createdAt", createdAt);
        return payment;
    }

    private List<Payment> payments(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> payment(100 - i, REQUESTED_AT.minusMinutes(i)))
                .toList();
    }

    @Test
    void 내_결제_한_건을_화면_상태와_KST_시각으로_돌려준다() {
        when(repository.findByIdAndOwnerId(100L, OWNER)).thenReturn(Optional.of(payment(100, REQUESTED_AT)));

        PaymentDetailResponse response = service().get(OWNER, 100L);

        assertThat(response.paymentId()).isEqualTo(100L);
        assertThat(response.orderId()).isEqualTo("ord_100");
        assertThat(response.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(response.displayStatus()).isEqualTo(DisplayStatus.CONFIRMING);
        assertThat(response.amount()).isEqualTo(30000);
        assertThat(response.requestedAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T10:30:00+09:00"));
        assertThat(response.approvedAt()).isNull();
    }

    @Test
    void 내_결제_중에_없으면_BILLING_004다() {
        when(repository.findByIdAndOwnerId(100L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(OWNER, 100L))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    void 한_건_더_읽어_다음_페이지가_있으면_마지막_행으로_토큰을_만든다() {
        when(repository.findOwnerHistory(OWNER, PageRequest.of(0, 3))).thenReturn(payments(3));

        CursorPageResponse<PaymentHistoryItem> page = service().list(OWNER, null, 2);

        assertThat(page.items()).extracting(PaymentHistoryItem::paymentId).containsExactly(100L, 99L);
        assertThat(page.items().get(0).displayStatus()).isEqualTo(DisplayStatus.CONFIRMING);
        PageCursor next = PageTokens.decode(page.nextPageToken());
        assertThat(next.id()).isEqualTo(99L);
        assertThat(next.sortValue()).isEqualTo(REQUESTED_AT.minusMinutes(1).toString());
    }

    @Test
    void 다음_페이지가_없으면_토큰을_비운다() {
        when(repository.findOwnerHistory(OWNER, PageRequest.of(0, 3))).thenReturn(payments(2));

        assertThat(service().list(OWNER, null, 2).nextPageToken()).isNull();
    }

    @Test
    void 토큰이_있으면_그_뒤부터_읽는다() {
        String token = PageTokens.encode(new PageCursor(99L, REQUESTED_AT.toString()));
        when(repository.findOwnerHistoryAfter(OWNER, REQUESTED_AT, 99L, PageRequest.of(0, 21))).thenReturn(List.of());

        assertThat(service().list(OWNER, token, 20).items()).isEmpty();
    }

    @Test
    void 시각을_읽을_수_없는_토큰은_첫_페이지로_본다() {
        String broken = PageTokens.encode(new PageCursor(99L, "not-a-time"));
        String empty = PageTokens.encode(new PageCursor(99L, null));
        when(repository.findOwnerHistory(OWNER, PageRequest.of(0, 21))).thenReturn(List.of());

        service().list(OWNER, broken, 20);
        service().list(OWNER, empty, 20);

        verify(repository, never()).findOwnerHistoryAfter(anyLong(), any(), anyLong(), any());
    }

    @Test
    void 페이지_크기는_기본_20_최대_100으로_맞춘다() {
        when(repository.findOwnerHistory(OWNER, PageRequest.of(0, 21))).thenReturn(List.of());
        when(repository.findOwnerHistory(OWNER, PageRequest.of(0, 101))).thenReturn(List.of());

        service().list(OWNER, null, 0);
        service().list(OWNER, null, 500);

        verify(repository).findOwnerHistory(OWNER, PageRequest.of(0, 21));
        verify(repository).findOwnerHistory(OWNER, PageRequest.of(0, 101));
    }
}
