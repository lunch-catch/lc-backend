package com.launchcatch.analytics.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.event.CampaignEventCounts;
import com.launchcatch.analytics.event.dto.CampaignFunnelResponse;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

class CampaignFunnelServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long CAMPAIGN_ID = 11L;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 7);

    private final CampaignQueryService campaigns = mock(CampaignQueryService.class);
    private final EventLogRepository repository = mock(EventLogRepository.class);
    private CampaignFunnelService service;

    @BeforeEach
    void setUp() {
        service = new CampaignFunnelService(campaigns, repository);
    }

    @Test
    void 본인_캠페인의_발생_건수로_비율을_계산한다() {
        ownedCampaign();
        when(repository.countCampaignEvents(CAMPAIGN_ID, FROM, TO))
                .thenReturn(new CampaignEventCounts(1000, 200, 600, 100, 60));

        CampaignFunnelResponse result = service.query(OWNER_ID, CAMPAIGN_ID, FROM, TO);

        assertThat(result.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);
        assertThat(result.impressionCount()).isEqualTo(1000);
        assertThat(result.wishCount()).isEqualTo(200);
        assertThat(result.passCount()).isEqualTo(600);
        assertThat(result.issuedCount()).isEqualTo(100);
        assertThat(result.redeemCount()).isEqualTo(60);
        assertThat(result.wishRate()).isEqualByComparingTo("0.2");
        assertThat(result.passRate()).isEqualByComparingTo("0.6");
        assertThat(result.wishToIssueRate()).isEqualByComparingTo("0.5");
        assertThat(result.issueToRedeemRate()).isEqualByComparingTo("0.6");
    }

    @Test
    void 이벤트가_없으면_건수는_0이고_비율은_null이다() {
        ownedCampaign();
        when(repository.countCampaignEvents(CAMPAIGN_ID, FROM, TO))
                .thenReturn(new CampaignEventCounts(0, 0, 0, 0, 0));

        CampaignFunnelResponse result = service.query(OWNER_ID, CAMPAIGN_ID, FROM, TO);

        assertThat(result.impressionCount()).isZero();
        assertThat(result.wishRate()).isNull();
        assertThat(result.passRate()).isNull();
        assertThat(result.wishToIssueRate()).isNull();
        assertThat(result.issueToRedeemRate()).isNull();
    }

    @Test
    void 소수점_여섯_자리로_반올림하고_100퍼센트를_넘는_비율도_보존한다() {
        ownedCampaign();
        when(repository.countCampaignEvents(CAMPAIGN_ID, FROM, TO))
                .thenReturn(new CampaignEventCounts(3, 1, 2, 2, 3));

        CampaignFunnelResponse result = service.query(OWNER_ID, CAMPAIGN_ID, FROM, TO);

        assertThat(result.wishRate()).isEqualByComparingTo("0.333333");
        assertThat(result.passRate()).isEqualByComparingTo("0.666667");
        assertThat(result.wishToIssueRate()).isEqualByComparingTo("2");
        assertThat(result.issueToRedeemRate()).isEqualByComparingTo("1.5");
    }

    @Test
    void 분모가_있고_분자가_없으면_비율은_0이다() {
        ownedCampaign();
        when(repository.countCampaignEvents(CAMPAIGN_ID, FROM, TO))
                .thenReturn(new CampaignEventCounts(10, 0, 0, 0, 0));

        CampaignFunnelResponse result = service.query(OWNER_ID, CAMPAIGN_ID, FROM, TO);

        assertThat(result.wishRate()).isZero();
        assertThat(result.passRate()).isZero();
        assertThat(result.wishToIssueRate()).isNull();
        assertThat(result.issueToRedeemRate()).isNull();
    }

    @Test
    void 양끝을_포함해_31일까지_조회한다() {
        ownedCampaign();
        LocalDate lastDay = FROM.plusDays(30);
        when(repository.countCampaignEvents(CAMPAIGN_ID, FROM, lastDay))
                .thenReturn(new CampaignEventCounts(0, 0, 0, 0, 0));

        assertThat(service.query(OWNER_ID, CAMPAIGN_ID, FROM, lastDay).to()).isEqualTo(lastDay);
    }

    @ParameterizedTest
    @MethodSource("invalidPeriods")
    void 잘못된_기간은_거절한다(LocalDate from, LocalDate to) {
        assertThatThrownBy(() -> service.query(OWNER_ID, CAMPAIGN_ID, from, to))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AnalyticsErrorCode.INVALID_PERIOD));
    }

    static Stream<Arguments> invalidPeriods() {
        return Stream.of(Arguments.of(null, TO), Arguments.of(FROM, null),
                Arguments.of(TO, FROM), Arguments.of(FROM, FROM.plusDays(31)));
    }

    @Test
    void 다른_점주의_캠페인은_거절한다() {
        ownedCampaign();

        assertThatThrownBy(() -> service.query(8L, CAMPAIGN_ID, FROM, TO))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 없는_캠페인도_같은_접근_거절로_처리한다() {
        when(campaigns.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> service.query(OWNER_ID, CAMPAIGN_ID, FROM, TO))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void ownedCampaign() {
        CampaignInfo campaign = mock(CampaignInfo.class);
        when(campaign.ownerId()).thenReturn(OWNER_ID);
        when(campaigns.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign));
    }
}
