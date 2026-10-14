package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.SettlementMismatchItem;
import com.launchcatch.billing.entity.MismatchCheckType;
import com.launchcatch.billing.entity.MismatchResolutionType;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import com.launchcatch.billing.repository.SettlementMismatchRow;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SettlementMismatchQueryServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 13);

    private final SettlementMismatchRepository repository = mock(SettlementMismatchRepository.class);
    private final SettlementMismatchQueryService service = new SettlementMismatchQueryService(repository);

    @BeforeEach
    void 저장소는_받은_페이지_요청으로_빈_결과를_돌려준다() {
        when(repository.search(any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(5), 0));
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), anyBoolean(), anyBoolean(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 항목에는_차이와_서울_시각을_담고_전체_건수를_함께_돌려준다() {
        SettlementMismatchRow open = new SettlementMismatchRow(55L, MismatchCheckType.CAMPAIGN_SPEND_VS_LEDGER, 12L,
                LocalDate.of(2026, 10, 1), 9800L, 9790L, null, 301L,
                LocalDateTime.of(2026, 10, 1, 13, 35), null, null);
        SettlementMismatchRow closed = new SettlementMismatchRow(56L, MismatchCheckType.PG_VS_CHARGE, 101L,
                LocalDate.of(2026, 10, 2), 30000L, 0L, MismatchResolutionType.OVERCHARGE_REVERSAL, 302L,
                LocalDateTime.of(2026, 10, 3, 0, 5), LocalDateTime.of(2026, 10, 3, 9, 0), 2L);
        /*
         * BeforeEach 의 스텁을 덮어쓴다.
         * when(...) 으로 다시 걸면 그 안의 호출이 앞의 스텁을 null 페이지 요청으로 실행해 실패하므로 doAnswer 로 건다.
         */
        doAnswer(invocation -> new PageImpl<>(List.of(open, closed), invocation.getArgument(5), 42))
                .when(repository).search(any(), anyBoolean(), anyBoolean(), any(), any(), any());

        PageResponse<SettlementMismatchItem> response = service.search(null, null, null, null, 0, 20, null, null);

        SettlementMismatchItem first = response.items().get(0);
        assertThat(first.diff()).isEqualTo(-10L);
        assertThat(first.createdAt().toString()).isEqualTo("2026-10-01T13:35+09:00");
        assertThat(first.resolvedAt()).isNull();
        assertThat(first.jobExecutionId()).isEqualTo(301L);
        SettlementMismatchItem second = response.items().get(1);
        assertThat(second.resolutionType()).isEqualTo(MismatchResolutionType.OVERCHARGE_REVERSAL);
        assertThat(second.resolvedAt().toString()).isEqualTo("2026-10-03T09:00+09:00");
        assertThat(second.resolvedBy()).isEqualTo(2L);
        assertThat(response.totalElements()).isEqualTo(42);
    }

    @Test
    void 필터는_그대로_넘기고_해결_여부는_두_플래그로_바꾼다() {
        service.search(MismatchCheckType.PG_VS_CHARGE, false, FROM, TO, 0, 20, null, null);
        service.search(null, true, null, null, 0, 20, null, null);
        service.search(null, null, null, null, 0, 20, null, null);

        verify(repository).search(eq(MismatchCheckType.PG_VS_CHARGE), eq(false), eq(true), eq(FROM), eq(TO), any());
        verify(repository).search(eq(null), eq(true), eq(false), eq(null), eq(null), any());
        verify(repository).search(eq(null), eq(false), eq(false), eq(null), eq(null), any());
    }

    @Test
    void 기본_정렬은_첫_탐지_시각_내림차순이고_ID가_뒤를_잇는다() {
        service.search(null, null, null, null, 2, 20, null, null);

        Pageable pageable = capturePageable();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    @Test
    void 영업일_오름차순으로_정렬할_수_있다() {
        service.search(null, null, null, null, 0, 20, "businessDate", "ASC");

        assertThat(capturePageable().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "businessDate", "id"));
    }

    @Test
    void 첫_탐지_시각과_desc를_명시해도_같은_정렬이다() {
        service.search(null, null, null, null, 0, 20, "createdAt", "desc");

        assertThat(capturePageable().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    @Test
    void 크기는_1_미만이면_20으로_100을_넘으면_100으로_보정한다() {
        service.search(null, null, null, null, 0, 0, null, null);
        service.search(null, null, null, null, 0, 101, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, org.mockito.Mockito.times(2))
                .search(any(), anyBoolean(), anyBoolean(), any(), any(), captor.capture());
        assertThat(captor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(20, 100);
    }

    @Test
    void 페이지_번호가_음수이거나_기간이_뒤집혔으면_거절한다() {
        assertInvalid(() -> service.search(null, null, null, null, -1, 20, null, null));
        assertInvalid(() -> service.search(null, null, TO, FROM, 0, 20, null, null));
    }

    @Test
    void 허용하지_않는_정렬_값은_거절한다() {
        assertInvalid(() -> service.search(null, null, null, null, 0, 20, "amount", null));
        assertInvalid(() -> service.search(null, null, null, null, 0, 20, null, "up"));
    }
}
