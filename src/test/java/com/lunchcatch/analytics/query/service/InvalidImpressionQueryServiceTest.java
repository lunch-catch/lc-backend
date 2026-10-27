package com.lunchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.batch.InvalidReasonCode;
import com.lunchcatch.analytics.exception.AnalyticsException;
import com.lunchcatch.analytics.query.InvalidImpressionResult;
import com.lunchcatch.analytics.query.InvalidImpressionSort;
import com.lunchcatch.analytics.query.repository.FraudFilter;
import com.lunchcatch.analytics.query.repository.FraudQueryRepository;
import com.lunchcatch.analytics.query.InvalidImpressionRow;
import com.lunchcatch.analytics.query.ReasonTotal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvalidImpressionQueryServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private FraudQueryRepository repository;

    private InvalidImpressionQueryService service() {
        return new InvalidImpressionQueryService(repository);
    }

    @Test
    void 기간을_생략하면_저장소를_읽지_않고_거절한다() {
        assertThatThrownBy(() -> service().query(null, TO, null, null, null, 0, 20, "count", "desc"))
                .isInstanceOf(AnalyticsException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void 기간이_31일을_넘으면_거절한다() {
        assertThatThrownBy(() -> service().query(FROM, FROM.plusDays(31), null, null, null, 0, 20, "count", "desc"))
                .isInstanceOf(AnalyticsException.class);
    }

    @Test
    void 지원하지_않는_정렬_기준과_방향은_거절한다() {
        assertThatThrownBy(() -> service().query(FROM, TO, null, null, null, 0, 20, "reasonCode", "desc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().query(FROM, TO, null, null, null, 0, 20, "count", "up"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 필터와_기간을_저장소에_넘기고_결과를_조립한다() {
        when(repository.sumInvalidByReason(any())).thenReturn(List.of(
                new ReasonTotal(InvalidReasonCode.NOT_OWNER, 240),
                new ReasonTotal(InvalidReasonCode.EXPIRED_OR_UNKNOWN, 1_210)));
        when(repository.countInvalid(any())).thenReturn(1L);
        InvalidImpressionRow row = new InvalidImpressionRow(55, 12, InvalidReasonCode.NOT_OWNER, 31);
        when(repository.findInvalid(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of(row));

        InvalidImpressionResult result = service().query(FROM, TO, null, 12L, 55L, 0, 20, "count", "desc");

        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);
        assertThat(result.items()).containsExactly(row);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
        ArgumentCaptor<FraudFilter> filter = ArgumentCaptor.forClass(FraudFilter.class);
        verify(repository).countInvalid(filter.capture());
        assertThat(filter.getValue().range().from()).isEqualTo(FROM);
        assertThat(filter.getValue().campaignId()).isEqualTo(12L);
        assertThat(filter.getValue().memberId()).isEqualTo(55L);
    }

    @Test
    void 사유별_합계는_사유_이름_순서로_두_사유를_모두_담고_없는_사유는_0이다() {
        when(repository.sumInvalidByReason(any())).thenReturn(List.of(
                new ReasonTotal(InvalidReasonCode.NOT_OWNER, 240)));
        when(repository.countInvalid(any())).thenReturn(0L);
        when(repository.findInvalid(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());

        InvalidImpressionResult result = service().query(FROM, TO, null, null, null, 0, 20, "count", "desc");

        assertThat(result.summaryByReason()).containsExactly(
                new ReasonTotal(InvalidReasonCode.EXPIRED_OR_UNKNOWN, 0),
                new ReasonTotal(InvalidReasonCode.NOT_OWNER, 240));
    }

    @Test
    void 사유를_고르면_합계에는_그_사유만_담는다() {
        when(repository.sumInvalidByReason(any())).thenReturn(List.of(
                new ReasonTotal(InvalidReasonCode.NOT_OWNER, 240)));
        when(repository.countInvalid(any())).thenReturn(0L);
        when(repository.findInvalid(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());

        InvalidImpressionResult result = service().query(
                FROM, TO, InvalidReasonCode.NOT_OWNER, null, null, 0, 20, "count", "desc");

        assertThat(result.summaryByReason()).containsExactly(new ReasonTotal(InvalidReasonCode.NOT_OWNER, 240));
    }

    @Test
    void 정렬_기준과_방향과_쪽_위치를_저장소에_넘긴다() {
        when(repository.sumInvalidByReason(any())).thenReturn(List.of());
        when(repository.countInvalid(any())).thenReturn(0L);
        when(repository.findInvalid(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());

        service().query(FROM, TO, null, null, null, 2, 50, "memberId", "ASC");

        verify(repository).findInvalid(any(), eq(InvalidImpressionSort.MEMBER_ID), eq(false), eq(100), eq(50));
    }

    @Test
    void 정렬을_생략한_기본값은_건수_내림차순이다() {
        when(repository.sumInvalidByReason(any())).thenReturn(List.of());
        when(repository.countInvalid(any())).thenReturn(0L);
        when(repository.findInvalid(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());

        service().query(FROM, TO, null, null, null, 0, 20, "count", "desc");

        verify(repository).findInvalid(any(), eq(InvalidImpressionSort.COUNT), eq(true), eq(0), eq(20));
    }
}
