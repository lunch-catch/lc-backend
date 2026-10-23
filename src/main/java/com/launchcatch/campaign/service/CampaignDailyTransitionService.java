package com.launchcatch.campaign.service;

import com.launchcatch.campaign.entity.ActorType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 00:00 묶음에서 캠페인이 맡는 두 단계의 일이다(#569, 요구사항명세서 102행 (1), (2)).
 *
 * 캠페인마다 CampaignStatusService 의 전이를 따로 부른다. 전이 하나가 자기 트랜잭션과 행 잠금을 가지므로 여기는
 * 트랜잭션을 열지 않는다. 한 캠페인이 실패해도 나머지는 계속 처리하고, 끝에서 실패가 하나라도 있으면 예외를 던져 단계를
 * 실패시킨다. 그러면 뒤 단계(정산)가 돌지 않고, 이어받기나 수동 재실행이 같은 영업일로 다시 돌 때 이미 끝난 캠페인은
 * 대상에서 빠져 결과가 같다(멱등).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignDailyTransitionService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusService campaignStatusService;

    // 집행 기간이 끝난 ACTIVE, PAUSED 캠페인을 ENDED 로 바꾼다. 처리한 개수를 돌려준다.
    public int endExpired(LocalDate businessDate) {
        List<Long> campaignIds = campaignRepository.findIdsToEnd(businessDate);
        List<Long> failed = new ArrayList<>();
        for (Long campaignId : campaignIds) {
            try {
                campaignStatusService.end(campaignId, ActorType.SYSTEM, businessDate);
            } catch (RuntimeException e) {
                failed.add(campaignId);
                log.error("event=CAMPAIGN_DAILY_END_FAILED campaignId={} businessDate={}", campaignId, businessDate, e);
            }
        }
        failIfAny("END_EXPIRED", failed, businessDate);
        return campaignIds.size();
    }

    /*
     * 집행 시작일이 된 SCHEDULED 캠페인을 ACTIVE 로 바꾸고, 같은 가게에 아직 ACTIVE 인 다른 캠페인은 먼저 ENDED 로
     * 바꾼다(요구사항명세서 47행). 기존 캠페인을 끝내는 것과 새 캠페인을 켜는 것은 서로 다른 트랜잭션이라, 그 사이에
     * 실패하면 기존 캠페인만 끝난 채로 남는다. 이 단계가 실패로 끝나고 다시 돌 때 새 캠페인이 이어서 켜진다.
     */
    public int startScheduled(LocalDate businessDate) {
        List<Campaign> campaigns = campaignRepository.findToStart(businessDate);
        List<Long> failed = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            try {
                for (Long otherId : campaignRepository.findOtherActiveIdsByStoreId(campaign.getStoreId(), campaign.getId())) {
                    campaignStatusService.end(otherId, ActorType.SYSTEM, businessDate);
                }
                campaignStatusService.activate(campaign.getId(), businessDate);
            } catch (RuntimeException e) {
                failed.add(campaign.getId());
                log.error("event=CAMPAIGN_DAILY_START_FAILED campaignId={} businessDate={}",
                        campaign.getId(), businessDate, e);
            }
        }
        failIfAny("START_SCHEDULED", failed, businessDate);
        return campaigns.size();
    }

    /*
     * 하루 예산 변경 예약(pendingDailyBudget)이 있는 ACTIVE 캠페인에 그 값을 적용한다(#840). 캠페인마다 자기 트랜잭션과
     * 행 잠금 아래에서 적용하고, 하나가 실패해도 나머지를 처리한 뒤 단계를 실패시킨다. 적용한 개수를 돌려준다.
     */
    public int applyPendingDailyBudgets(LocalDate businessDate) {
        List<Long> campaignIds = campaignRepository.findIdsWithPendingDailyBudget();
        List<Long> failed = new ArrayList<>();
        int applied = 0;
        for (Long campaignId : campaignIds) {
            try {
                if (campaignStatusService.applyPendingDailyBudget(campaignId)) {
                    applied++;
                }
            } catch (RuntimeException e) {
                failed.add(campaignId);
                log.error("event=CAMPAIGN_DAILY_BUDGET_APPLY_FAILED campaignId={} businessDate={}",
                        campaignId, businessDate, e);
            }
        }
        failIfAny("APPLY_PENDING_BUDGET", failed, businessDate);
        return applied;
    }

    private void failIfAny(String step, List<Long> failed, LocalDate businessDate) {
        if (!failed.isEmpty()) {
            throw new IllegalStateException("campaign daily transition failed: step=" + step
                    + " businessDate=" + businessDate + " failedCount=" + failed.size() + " campaignIds=" + failed);
        }
    }
}
