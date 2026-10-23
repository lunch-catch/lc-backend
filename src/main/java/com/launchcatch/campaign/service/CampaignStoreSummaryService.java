package com.launchcatch.campaign.service;

import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignSummaryRow;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 00:00 묶음에서 진행 중인 캠페인의 가게 요약을 새 영업일 몫으로 만든다(#838, 요구사항명세서 102행).
 * 가게 요약은 영업일마다 한 행이라 하루 넘게 진행하는 캠페인은 날이 바뀌면 요약이 없어 가게 목록과 상세에서 사라진다.
 *
 * 한 트랜잭션 안에서 id 순으로 500건씩 읽어 청크마다 updateAll 로 저장한다(store 계약이 호출자의 쓰기 트랜잭션을
 * 요구한다). 청크는 저장한 뒤 참조를 버리므로 ACTIVE 캠페인 전체를 한꺼번에 메모리에 올리지 않는다. 트랜잭션은 하나라
 * 어느 청크에서든 실패하면 앞서 저장한 청크까지 모두 롤백되고 예외가 그대로 올라가 단계가 실패한다. 그러면 뒤
 * 단계(정산)가 돌지 않는다. 요약은 (영업일, 캠페인) 기준 upsert 라 같은 영업일에 다시 돌아도 결과가 같다(멱등).
 */
@Service
@RequiredArgsConstructor
public class CampaignStoreSummaryService {

    // store 의 updateAll 이 안에서 나누는 묶음(500)과 맞춘다.
    private static final int CHUNK_SIZE = 500;

    private final CampaignRepository campaignRepository;
    private final StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;

    // 집행 기간이 끝나지 않은 ACTIVE 캠페인의 요약을 businessDate 몫으로 만든다. 만든 개수를 돌려준다.
    @Transactional
    public int prepare(LocalDate businessDate) {
        int prepared = 0;
        long lastId = 0L;
        List<CampaignSummaryRow> rows =
                campaignRepository.findActiveSummaryRowsAfter(lastId, businessDate, PageRequest.ofSize(CHUNK_SIZE));
        while (!rows.isEmpty()) {
            storeCampaignSummaryUpdater.updateAll(toUpdates(rows, businessDate));
            prepared += rows.size();
            lastId = rows.get(rows.size() - 1).campaignId();
            rows = campaignRepository.findActiveSummaryRowsAfter(lastId, businessDate, PageRequest.ofSize(CHUNK_SIZE));
        }
        return prepared;
    }

    private List<StoreCampaignSummaryUpdate> toUpdates(List<CampaignSummaryRow> rows, LocalDate businessDate) {
        return rows.stream()
                .map(row -> new StoreCampaignSummaryUpdate(row.storeId(), row.campaignId(),
                        CampaignStatusService.toSummaryCommand(row, businessDate)))
                .toList();
    }
}
