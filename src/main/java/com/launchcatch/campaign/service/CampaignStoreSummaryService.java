package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 00:00 묶음에서 진행 중인 캠페인의 가게 요약을 새 영업일 몫으로 만든다(#838, 요구사항명세서 102행).
 * 가게 요약은 영업일마다 한 행이라 하루 넘게 진행하는 캠페인은 날이 바뀌면 요약이 없어 가게 목록과 상세에서 사라진다.
 *
 * 한 트랜잭션에서 updateAll 로 한 번에 저장한다(store 계약이 호출자의 쓰기 트랜잭션을 요구한다). 요약 한 건이라도
 * 실패하면 모두 롤백되고 예외가 그대로 올라가 단계가 실패한다. 그러면 뒤 단계(정산)가 돌지 않는다.
 * 요약은 (영업일, 캠페인) 기준 upsert 라 같은 영업일에 다시 돌아도 결과가 같다(멱등).
 */
@Service
@RequiredArgsConstructor
public class CampaignStoreSummaryService {

    private final CampaignRepository campaignRepository;
    private final StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;

    // ACTIVE 캠페인의 요약을 businessDate 몫으로 만든다. 만든 개수를 돌려준다.
    @Transactional
    public int prepare(LocalDate businessDate) {
        List<Campaign> campaigns = campaignRepository.findByStatusOrderById(CampaignStatus.ACTIVE);
        if (campaigns.isEmpty()) {
            return 0;
        }
        List<StoreCampaignSummaryUpdate> updates = campaigns.stream()
                .map(campaign -> new StoreCampaignSummaryUpdate(campaign.getStoreId(), campaign.getId(),
                        CampaignStatusService.toSummaryCommand(campaign, businessDate)))
                .toList();
        storeCampaignSummaryUpdater.updateAll(updates);
        return updates.size();
    }
}
