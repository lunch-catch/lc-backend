package com.lunchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.config.AnalyticsFraudProperties;
import com.lunchcatch.analytics.exception.AnalyticsException;
import com.lunchcatch.analytics.query.CampaignWarning;
import com.lunchcatch.analytics.query.CampaignWarningResult;
import com.lunchcatch.analytics.query.CampaignWarningType;
import com.lunchcatch.analytics.query.repository.CampaignFraudRow;
import com.lunchcatch.analytics.query.repository.FraudQueryRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignWarningQueryServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static final AnalyticsFraudProperties PROPERTIES = new AnalyticsFraudProperties(5, 0.5, 100, 100);

    @Mock
    private FraudQueryRepository repository;

    private CampaignWarningQueryService service() {
        return new CampaignWarningQueryService(repository, PROPERTIES);
    }

    private void rows(CampaignFraudRow... rows) {
        when(repository.findCampaignFraud(FROM, TO)).thenReturn(List.of(rows));
    }

    private static CampaignFraudRow row(long campaignId, long valid, long invalid, double share, boolean suspected) {
        return new CampaignFraudRow(campaignId, valid, invalid, 4, share, suspected);
    }

    @Test
    void 기간을_생략하거나_31일을_넘으면_거절한다() {
        assertThatThrownBy(() -> service().query(null, TO, null, 0, 20, "invalidRatio", "desc"))
                .isInstanceOf(AnalyticsException.class);
        assertThatThrownBy(() -> service().query(FROM, FROM.plusDays(31), null, 0, 20, "invalidRatio", "desc"))
                .isInstanceOf(AnalyticsException.class);
    }

    @Test
    void 지원하지_않는_정렬_기준과_방향은_거절한다() {
        assertThatThrownBy(() -> service().query(FROM, TO, null, 0, 20, "validCount", "desc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().query(FROM, TO, null, 0, 20, "invalidRatio", "up"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 무효_비율과_두_경고를_캠페인마다_판정해_내려준다() {
        rows(row(12L, 4_000, 1_100, 0.92, true));

        CampaignWarningResult result = service().query(FROM, TO, null, 0, 20, "invalidRatio", "desc");

        assertThat(result.items()).containsExactly(
                new CampaignWarning(12L, 4_000, 1_100, 0.275, true, 4, 0.92, true));
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void 경고가_하나도_없는_캠페인은_내려주지_않는다() {
        rows(row(12L, 1_000, 10, 0.2, false), row(13L, 1_000, 500, 0.2, false));

        CampaignWarningResult result = service().query(FROM, TO, null, 0, 20, "campaignId", "asc");

        assertThat(result.items()).extracting(CampaignWarning::campaignId).containsExactly(13L);
    }

    @Test
    void 유효_노출이_0이고_무효가_최소_건수_이상이면_비율은_null이고_경고한다() {
        rows(row(999L, 0, 120, 0.0, false));

        CampaignWarning warning = service().query(FROM, TO, null, 0, 20, "invalidRatio", "desc").items().getFirst();

        assertThat(warning.invalidRatio()).isNull();
        assertThat(warning.invalidRatioWarning()).isTrue();
    }

    @Test
    void 무효가_최소_건수_미만이면_비율이_높아도_경고하지_않는다() {
        rows(row(12L, 1, 1, 0.0, false));

        assertThat(service().query(FROM, TO, null, 0, 20, "invalidRatio", "desc").items()).isEmpty();
    }

    @Test
    void 타입으로_고르면_그_경고가_걸린_캠페인만_내려준다() {
        rows(row(12L, 400, 100, 0.1, false), row(13L, 1_000, 0, 0.9, true));

        assertThat(service().query(FROM, TO, CampaignWarningType.INVALID_RATIO, 0, 20, "campaignId", "asc").items())
                .extracting(CampaignWarning::campaignId).containsExactly(12L);
        assertThat(service().query(FROM, TO, CampaignWarningType.CONCENTRATION, 0, 20, "campaignId", "asc").items())
                .extracting(CampaignWarning::campaignId).containsExactly(13L);
    }

    @Test
    void 타입을_생략하면_둘_중_하나라도_걸린_캠페인을_모두_내려준다() {
        rows(row(12L, 400, 100, 0.1, false), row(13L, 1_000, 0, 0.9, true), row(14L, 1_000, 0, 0.1, false));

        assertThat(service().query(FROM, TO, null, 0, 20, "campaignId", "asc").items())
                .extracting(CampaignWarning::campaignId).containsExactly(12L, 13L);
    }

    @Test
    void 비율_내림차순은_비율이_없는_경고를_맨_앞에_두고_같으면_캠페인_번호_순이다() {
        rows(row(1L, 400, 100, 0.1, false), row(2L, 0, 500, 0.0, false), row(3L, 100, 150, 0.1, false),
                row(4L, 400, 100, 0.1, false));

        assertThat(service().query(FROM, TO, null, 0, 20, "invalidRatio", "desc").items())
                .extracting(CampaignWarning::campaignId).containsExactly(2L, 3L, 1L, 4L);
    }

    @Test
    void 비율_오름차순은_비율이_없는_경고를_맨_뒤에_둔다() {
        rows(row(1L, 400, 100, 0.1, false), row(2L, 0, 500, 0.0, false), row(3L, 100, 150, 0.1, false));

        assertThat(service().query(FROM, TO, null, 0, 20, "invalidRatio", "asc").items())
                .extracting(CampaignWarning::campaignId).containsExactly(1L, 3L, 2L);
    }

    @Test
    void 점유율_기준으로_정렬한다() {
        rows(row(1L, 1_000, 0, 0.6, true), row(2L, 1_000, 0, 0.9, true), row(3L, 1_000, 0, 0.7, true));

        assertThat(service().query(FROM, TO, null, 0, 20, "topMembersShare", "desc").items())
                .extracting(CampaignWarning::campaignId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void 쪽을_자르고_전체_건수는_쪽과_무관하다() {
        rows(row(1L, 1_000, 0, 0.9, true), row(2L, 1_000, 0, 0.9, true), row(3L, 1_000, 0, 0.9, true));

        CampaignWarningResult result = service().query(FROM, TO, null, 1, 2, "campaignId", "asc");

        assertThat(result.items()).extracting(CampaignWarning::campaignId).containsExactly(3L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(3);
    }

    @Test
    void 범위를_넘긴_쪽은_빈_목록이다() {
        rows(row(1L, 1_000, 0, 0.9, true));

        CampaignWarningResult result = service().query(FROM, TO, null, 5, 20, "campaignId", "asc");

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(1);
    }
}
