package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CampaignQueryServiceImplIntegrationTest extends com.launchcatch.campaign.AbstractCampaignIntegrationTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignQueryServiceImpl service;

    @Test
    void ACTIVE와_예약이_있는_PAUSED만_그_영업일에_서빙_대상이다() {
        // 가게당 활성 캠페인 1건 제약(uk_campaign_active_store)이 있어 가게를 하나씩 따로 쓴다.
        Campaign active = save(draft(1L));
        active.schedule(now());
        active.activate(now());

        Campaign pausedByOwner = save(draft(2L));
        pausedByOwner.schedule(now());
        pausedByOwner.activate(now());
        pausedByOwner.pause(PauseReason.OWNER, now());

        Campaign pausedByNoPoints = save(draft(3L));
        pausedByNoPoints.schedule(now());
        pausedByNoPoints.activate(now());
        pausedByNoPoints.pause(PauseReason.NO_POINTS, now());

        Campaign pausedByAdmin = save(draft(4L));
        pausedByAdmin.schedule(now());
        pausedByAdmin.activate(now());
        pausedByAdmin.pause(PauseReason.ADMIN, now());

        Campaign draft = save(draft(5L));

        Campaign scheduled = save(draft(6L));
        scheduled.schedule(now());

        Campaign ended = save(draft(7L));
        ended.schedule(now());
        ended.activate(now());
        ended.end(now());

        Campaign outOfPeriod =
                save(draftWithPeriod(8L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
        outOfPeriod.schedule(now());
        outOfPeriod.activate(now());

        campaignRepository.flush();

        List<CampaignInfo> result = service.findServableOn(BUSINESS_DATE);

        assertThat(result)
                .extracting(CampaignInfo::campaignId)
                .containsExactlyInAnyOrder(active.getId(), pausedByOwner.getId(), pausedByNoPoints.getId());
    }

    @Test
    void 집행_기간의_시작일과_종료일_경계를_포함한다() {
        Campaign startsToday = save(draftWithPeriod(1L, BUSINESS_DATE, BUSINESS_DATE.plusDays(10)));
        startsToday.schedule(now());
        startsToday.activate(now());

        Campaign endsToday = save(draftWithPeriod(2L, BUSINESS_DATE.minusDays(10), BUSINESS_DATE));
        endsToday.schedule(now());
        endsToday.activate(now());

        Campaign endedYesterday =
                save(draftWithPeriod(3L, BUSINESS_DATE.minusDays(10), BUSINESS_DATE.minusDays(1)));
        endedYesterday.schedule(now());
        endedYesterday.activate(now());

        campaignRepository.flush();

        List<CampaignInfo> result = service.findServableOn(BUSINESS_DATE);

        assertThat(result)
                .extracting(CampaignInfo::campaignId)
                .containsExactlyInAnyOrder(startsToday.getId(), endsToday.getId());
    }

    private Campaign save(Campaign campaign) {
        return campaignRepository.save(campaign);
    }

    private Campaign draft(Long storeId) {
        return draftWithPeriod(storeId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }

    private Campaign draftWithPeriod(Long storeId, LocalDate startDate, LocalDate endDate) {
        return Campaign.create(
                10L, storeId,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, startDate, endDate,
                now());
    }

    private LocalDateTime now() {
        return LocalDateTime.of(2026, 10, 13, 9, 0);
    }
}
