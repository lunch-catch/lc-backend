package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatsQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.ActorType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.entity.CampaignStatusLog;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * CampaignStatsQueryService.activeCampaignCount는 campaign_status_log를 윈도 함수로 읽는
 * 직접 작성한 네이티브 쿼리라, 메서드 커버리지 게이트가 보는 서비스 계층 단위 테스트만으로는
 * 쿼리 자체가 한 번도 실행되지 않은 채로 통과한다(#354, LLM_구현_플로우.md "범위와 확인").
 * 그래서 입구(다른 도메인의 호출)대로 contract 빈을 주입받아 실제 DB까지 확인한다.
 */
class CampaignStatsQueryIntegrationTest extends AbstractCampaignIntegrationTest {

    @Autowired
    private CampaignStatsQueryService campaignStatsQueryService;

    @Autowired
    private CampaignStatusLogRepository campaignStatusLogRepository;

    @Test
    void 그날_중간에_ACTIVE가_된_캠페인은_그날_집계에_포함된다() {
        LocalDate businessDate = LocalDate.of(2020, 3, 10);
        Long campaignId = createCampaign();
        log(campaignId, null, "SCHEDULED", businessDate.atTime(8, 0));
        log(campaignId, "SCHEDULED", "ACTIVE", businessDate.atTime(9, 0));

        asOfRealNow();
        assertThat(campaignStatsQueryService.activeCampaignCount(businessDate)).isEqualTo(1);
    }

    @Test
    void 그날_중간에_PAUSED되거나_ENDED된_캠페인은_그날_집계에서_빠진다() {
        LocalDate businessDate = LocalDate.of(2020, 4, 10);
        Long paused = createCampaign();
        log(paused, null, "ACTIVE", businessDate.minusDays(1).atTime(9, 0));
        log(paused, "ACTIVE", "PAUSED", businessDate.atTime(15, 0));

        Long ended = createCampaign();
        log(ended, null, "ACTIVE", businessDate.minusDays(1).atTime(9, 0));
        log(ended, "ACTIVE", "ENDED", businessDate.atTime(16, 0));

        asOfRealNow();
        assertThat(campaignStatsQueryService.activeCampaignCount(businessDate)).isZero();
    }

    @Test
    void 지금_상태가_ENDED여도_과거_영업일에는_그때_ACTIVE_상태로_센다() {
        LocalDate activeDay = LocalDate.of(2020, 6, 1);
        LocalDate endedDay = LocalDate.of(2020, 6, 5);
        Long campaignId = createCampaign();
        log(campaignId, null, "ACTIVE", activeDay.atTime(9, 0));
        log(campaignId, "ACTIVE", "ENDED", endedDay.atTime(18, 0));

        asOfRealNow();
        assertThat(campaignStatsQueryService.activeCampaignCount(activeDay)).isEqualTo(1);
        assertThat(campaignStatsQueryService.activeCampaignCount(endedDay)).isZero();
    }

    private Long createCampaign() {
        long storeId = System.nanoTime();
        Campaign campaign = Campaign.create(
                1L, storeId,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
        return campaignRepository.saveAndFlush(campaign).getId();
    }

    /** fromStatus가 null이면 로그 저장 전제(두 값이 달라야 함)를 지키려고 DRAFT를 쓴다. */
    private void log(Long campaignId, String fromStatus, String toStatus, LocalDateTime at) {
        when(clock.instant()).thenReturn(at.atZone(ClockConfig.ZONE).toInstant());
        String from = fromStatus == null ? "DRAFT" : fromStatus;
        campaignStatusLogRepository.saveAndFlush(CampaignStatusLog.of(
                campaignId, CampaignStatus.valueOf(from), CampaignStatus.valueOf(toStatus),
                ActorType.SYSTEM, null));
    }

    /*
     * log()가 clock.instant()를 과거로 고정해 둔 채로 끝난다. activeCampaignCount가
     * "오늘이거나 미래면 거부"하는 가드에서 그 고정된 과거를 "오늘"로 잘못 보지 않게,
     * 조회 직전에 실제 현재 시각으로 되돌린다.
     */
    private void asOfRealNow() {
        when(clock.instant()).thenReturn(Instant.now());
    }
}
