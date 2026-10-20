package com.launchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.analytics.query.CampaignReportTotal;
import com.launchcatch.analytics.query.repository.CampaignReportQueryRepository;
import com.launchcatch.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 관리자 캠페인 리포트(A-14)의 요청 검증과 응답 순서를 본다(#625).
 *
 * 화면은 캠페인 목록 응답과 이 응답을 캠페인 ID 로 합친다. 그래서 요청 순서대로, 집계가 없는 캠페인도 0 으로
 * 빠짐없이 내려야 목록의 칸이 비지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class CampaignReportQueryServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Mock
    private CampaignReportQueryRepository repository;

    @InjectMocks
    private CampaignReportQueryService service;

    @Test
    void 요청한_순서대로_내리고_집계가_없는_캠페인은_0_이다() {
        when(repository.sumByCampaign(List.of(31L, 12L, 15L), FROM, TO)).thenReturn(List.of(
                new CampaignReportTotal(12L, 84_000, 9_100), new CampaignReportTotal(31L, -200, 40)));

        List<CampaignReportTotal> items = service.query("31,12,15", FROM, TO);

        assertThat(items).containsExactly(
                new CampaignReportTotal(31L, -200, 40),
                new CampaignReportTotal(12L, 84_000, 9_100),
                new CampaignReportTotal(15L, 0, 0));
    }

    @Test
    void 기간을_생략하면_집계된_전체_기간으로_조회한다() {
        when(repository.sumByCampaign(List.of(12L), null, null)).thenReturn(List.of());

        assertThat(service.query("12", null, null)).containsExactly(new CampaignReportTotal(12L, 0, 0));
    }

    @Test
    void 같은_ID_가_두_번_오면_처음_자리에_한_번만_내린다() {
        when(repository.sumByCampaign(List.of(12L, 15L), FROM, TO)).thenReturn(List.of());

        assertThat(service.query("12, 15,12", FROM, TO)).extracting(CampaignReportTotal::campaignId)
                .containsExactly(12L, 15L);
    }

    @Test
    void 캠페인_ID_는_100개까지_받는다() {
        String hundred = LongStream.rangeClosed(1, 100).mapToObj(Long::toString).collect(Collectors.joining(","));
        when(repository.sumByCampaign(LongStream.rangeClosed(1, 100).boxed().toList(), FROM, TO)).thenReturn(List.of());

        assertThat(service.query(hundred, FROM, TO)).hasSize(100);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "12,,15", "12,abc", "0", "-3", "12,"})
    void 캠페인_ID_가_없거나_양수가_아니거나_형식이_틀리면_COMMON_002(String campaignIds) {
        assertThatThrownBy(() -> service.query(campaignIds, FROM, TO))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verifyNoInteractions(repository);
    }

    @Test
    void 캠페인_ID_가_100개를_넘으면_COMMON_002() {
        String overLimit = LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(","));

        assertThatThrownBy(() -> service.query(overLimit, FROM, TO))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @CsvSource(value = {"2026-09-01, null", "null, 2026-09-30", "2026-09-30, 2026-09-01", "2026-09-01, 2026-12-02"},
            nullValues = "null")
    void 기간을_하나만_주거나_역전되거나_92일을_넘으면_ANALYTICS_001(LocalDate from, LocalDate to) {
        assertThatThrownBy(() -> service.query("12", from, to))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AnalyticsErrorCode.INVALID_PERIOD));
        verifyNoInteractions(repository);
    }

    @Test
    void 기간은_양끝을_포함해_92일까지_받는다() {
        LocalDate to = FROM.plusDays(91);
        when(repository.sumByCampaign(List.of(12L), FROM, to)).thenReturn(List.of());

        assertThat(service.query("12", FROM, to)).hasSize(1);
    }
}
