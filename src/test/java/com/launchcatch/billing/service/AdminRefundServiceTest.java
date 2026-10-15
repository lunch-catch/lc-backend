package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.AdminRefundRequestItem;
import com.launchcatch.billing.entity.RefundRequest;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.OwnerBalanceRow;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.RefundRequestRepository;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

class AdminRefundServiceTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 10, 14, 10, 0);

    private final RefundRequestRepository refundRepository = mock(RefundRequestRepository.class);
    private final PointLedgerRepository ledgerRepository = mock(PointLedgerRepository.class);
    private final CampaignQueryService campaignQueryService = mock(CampaignQueryService.class);
    private final AdminRefundService service =
            new AdminRefundService(refundRepository, ledgerRepository, campaignQueryService);

    private static RefundRequest stored(long id, Long ownerId, long amount) {
        RefundRequest request = RefundRequest.requestAll(ownerId, amount, "서비스 중단");
        ReflectionTestUtils.setField(request, "id", id);
        ReflectionTestUtils.setField(request, "createdAt", REQUESTED_AT);
        return request;
    }

    private void repositoryReturns(List<RefundRequest> rows, long total) {
        doAnswer(invocation -> new PageImpl<>(rows, invocation.getArgument(4), total))
                .when(refundRepository).search(any(), any(), any(), any(), any());
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository).search(any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 페이지의_점주를_모아_잔액과_집행_중_캠페인을_한_번씩_묻는다() {
        RefundRequest first = stored(31L, 7L, 12000L);
        RefundRequest second = stored(32L, 8L, 5000L);
        RefundRequest third = stored(33L, 7L, 3000L);
        third.reject(2L, "약관상 환불 불가 기간", REQUESTED_AT.plusHours(1));
        repositoryReturns(List.of(first, second, third), 41);
        when(ledgerRepository.sumBalanceByOwnerIds(List.of(7L, 8L)))
                .thenReturn(List.of(new OwnerBalanceRow(7L, 12000L)));
        when(campaignQueryService.findOwnerIdsWithLiveCampaign(List.of(7L, 8L))).thenReturn(Set.of(8L));

        PageResponse<AdminRefundRequestItem> response = service.search(null, null, null, null, 0, 20, null, null);

        List<AdminRefundRequestItem> items = response.items();
        assertThat(items).extracting(AdminRefundRequestItem::currentBalance).containsExactly(12000L, 0L, 12000L);
        assertThat(items).extracting(AdminRefundRequestItem::hasActiveCampaign).containsExactly(false, true, false);
        assertThat(items.get(0).requestedAt().toString()).isEqualTo("2026-10-14T10:00+09:00");
        assertThat(items.get(0).processedAt()).isNull();
        assertThat(items.get(2).rejectReason()).isEqualTo("약관상 환불 불가 기간");
        assertThat(items.get(2).processedAt().toString()).isEqualTo("2026-10-14T11:00+09:00");
        assertThat(items.get(2).processedBy()).isEqualTo(2L);
        assertThat(response.totalElements()).isEqualTo(41);
        verify(ledgerRepository, times(1)).sumBalanceByOwnerIds(any());
    }

    @Test
    void 결과가_없으면_잔액과_캠페인을_묻지_않는다() {
        repositoryReturns(List.of(), 0);

        PageResponse<AdminRefundRequestItem> response = service.search(null, null, null, null, 3, 20, null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(3);
        verify(ledgerRepository, never()).sumBalanceByOwnerIds(any());
        verify(campaignQueryService, never()).findOwnerIdsWithLiveCampaign(any());
    }

    @Test
    void 필터는_그대로_넘기고_기간은_요청_시각의_KST_날짜_양_끝을_포함한다() {
        repositoryReturns(List.of(), 0);

        service.search(RefundStatus.REQUESTED, 7L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13),
                0, 20, null, null);

        verify(refundRepository).search(eq(RefundStatus.REQUESTED), eq(7L),
                eq(LocalDateTime.of(2026, 10, 1, 0, 0)), eq(LocalDateTime.of(2026, 10, 14, 0, 0)), any());
    }

    @Test
    void 기본_정렬은_요청_시각_내림차순이고_ID가_뒤를_잇는다() {
        repositoryReturns(List.of(), 0);

        service.search(null, null, null, null, 0, 20, null, null);

        assertThat(capturePageable().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    @Test
    void 요청_시각을_명시해도_같은_정렬이고_금액_오름차순도_된다() {
        repositoryReturns(List.of(), 0);

        service.search(null, null, null, null, 0, 20, "requestedAt", "desc");
        service.search(null, null, null, null, 0, 20, "amount", "ASC");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository, times(2)).search(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getAllValues().get(0).getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        assertThat(captor.getAllValues().get(1).getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "amount", "id"));
    }

    @Test
    void 처리_시각으로_정렬하면_처리_전_요청은_맨_뒤다() {
        repositoryReturns(List.of(), 0);

        service.search(null, null, null, null, 0, 20, "processedAt", "asc");

        Sort.Order processedAt = capturePageable().getSort().getOrderFor("processedAt");
        assertThat(processedAt.getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(processedAt.getNullHandling()).isEqualTo(Sort.NullHandling.NULLS_LAST);
    }

    @Test
    void 크기는_1_미만이면_20으로_100을_넘으면_100으로_보정한다() {
        repositoryReturns(List.of(), 0);

        service.search(null, null, null, null, 0, 0, null, null);
        service.search(null, null, null, null, 0, 101, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository, times(2)).search(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(20, 100);
    }

    @Test
    void 페이지_번호가_음수이거나_기간이_뒤집혔거나_정렬_값이_틀리면_거절한다() {
        assertInvalid(() -> service.search(null, null, null, null, -1, 20, null, null));
        assertInvalid(() -> service.search(null, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1),
                0, 20, null, null));
        assertInvalid(() -> service.search(null, null, null, null, 0, 20, "ownerId", null));
        assertInvalid(() -> service.search(null, null, null, null, 0, 20, null, "up"));
    }
}
