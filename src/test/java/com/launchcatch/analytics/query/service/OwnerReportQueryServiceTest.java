package com.launchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.analytics.query.dto.OwnerReportResponse;
import com.launchcatch.analytics.query.repository.OwnerReportDailyRow;
import com.launchcatch.analytics.query.repository.OwnerReportQueryRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerReportQueryServiceTest {

    private static final long OWNER_ID = 901L;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 16);
    private static final LocalDate TO = LocalDate.of(2026, 10, 17);

    @Mock
    private OwnerReportQueryRepository repository;

    @InjectMocks
    private OwnerReportQueryService service;

    @Test
    void 점주의_일별_지표와_기간의_소진액_합계를_반환한다() {
        when(repository.findBetween(OWNER_ID, FROM, TO)).thenReturn(List.of(
                new OwnerReportDailyRow(FROM, 801, 7000, 2, 2400, 8, 180, 190, 76, 4000, 100, 3900),
                new OwnerReportDailyRow(TO, 801, 8000, 2, 2700, 12, 200, 210, 84, 4700, 200, 4500)));

        OwnerReportResponse response = service.query(OWNER_ID, FROM, TO);

        assertThat(response.from()).isEqualTo(FROM);
        assertThat(response.to()).isEqualTo(TO);
        assertThat(response.totalSpentAmount()).isEqualTo(8400);
        assertThat(response.items()).containsExactly(
                new OwnerReportResponse.Item(FROM, 801, 7000, 2, 2400, 8, 180, 190, 76, 4000, 100, 3900),
                new OwnerReportResponse.Item(TO, 801, 8000, 2, 2700, 12, 200, 210, 84, 4700, 200, 4500));
    }

    @Test
    void 집계가_없으면_빈_목록과_합계_0을_반환한다() {
        when(repository.findBetween(OWNER_ID, FROM, TO)).thenReturn(List.of());

        OwnerReportResponse response = service.query(OWNER_ID, FROM, TO);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalSpentAmount()).isZero();
    }

    @Test
    void 정정으로_소진액이_음수여도_그대로_합산하고_int64로_반환한다() {
        when(repository.findBetween(OWNER_ID, FROM, TO)).thenReturn(List.of(
                new OwnerReportDailyRow(FROM, 801, 3_000_000_000L, 2, 0, 0, 0, 0, 0,
                        3_000_000_000L, 0, 3_000_000_000L),
                new OwnerReportDailyRow(TO, 801, 7000, 2, 0, 0, 0, 0, 0, 0, 100, -100)));

        OwnerReportResponse response = service.query(OWNER_ID, FROM, TO);

        assertThat(response.totalSpentAmount()).isEqualTo(2_999_999_900L);
        assertThat(response.items().get(1).spentAmount()).isEqualTo(-100);
    }

    @ParameterizedTest
    @CsvSource({"2026-10-16, 2026-10-16", "2026-09-18, 2026-10-17"})
    void 하루부터_양끝을_포함한_30일까지_허용한다(LocalDate from, LocalDate to) {
        when(repository.findBetween(OWNER_ID, from, to)).thenReturn(List.of());

        OwnerReportResponse response = service.query(OWNER_ID, from, to);

        assertThat(response.from()).isEqualTo(from);
        assertThat(response.to()).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource(value = {"2026-10-17, 2026-10-16", "2026-09-17, 2026-10-17",
            "null, 2026-10-17", "2026-10-16, null"}, nullValues = "null")
    void 역전되거나_31일_이상이거나_누락된_기간은_거부한다(LocalDate from, LocalDate to) {
        assertThatThrownBy(() -> service.query(OWNER_ID, from, to))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AnalyticsErrorCode.INVALID_PERIOD));
    }
}
