package com.lunchcatch.campaign.service;

import com.lunchcatch.campaign.repository.CampaignRepository;
import com.lunchcatch.campaign.repository.CampaignSummaryRow;
import com.lunchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.lunchcatch.store.contract.StoreCampaignSummaryUpdater;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 00:00 묶음에서 진행 중인 캠페인의 가게 요약을 새 영업일 몫으로 만든다(#838, 요구사항명세서 102행).
 * 가게 요약은 영업일마다 한 행이라 하루 넘게 진행하는 캠페인은 날이 바뀌면 요약이 없어 가게 목록과 상세에서 사라진다.
 *
 * id 순으로 500건씩 읽어 청크마다 자기 트랜잭션에서 updateAll 로 저장한다(store 계약이 호출자의 쓰기 트랜잭션을
 * 요구한다). 청크는 저장한 뒤 참조를 버리므로 ACTIVE 캠페인 전체를 한꺼번에 메모리에 올리지 않는다.
 *
 * 캠페인 한 건의 데이터 오류(IllegalArgumentException, 예: 할인 대상 메뉴가 그 가게 메뉴가 아님)가 단계 전체를
 * 멈추지 않게 한다(#903). 종료(10)와 시작(20)이 데이터 오류 캠페인만 건너뛰는 것과 같다. 정산(50 이후)이 그 단계 뒤에
 * 있어, 단계가 실패하면 그날 모든 점주의 광고가 나가지 않는다. store 의 updateAll 은 MANDATORY 트랜잭션에 참여하고 한
 * 청크를 한 번에 검증하므로, 오류가 나면 그 청크의 트랜잭션이 되돌아가고 어느 캠페인 때문인지 알 수 없다. 그래서 청크가
 * 데이터 오류로 실패하면 그 청크만 캠페인 한 건씩 각자 트랜잭션에서 다시 저장한다. 오류 난 캠페인은 ERROR 로그를
 * 남기고 건너뛰며 그날 요약이 없다. 정상일 때는 지금처럼 청크 단위로 저장해 쿼리 수가 늘지 않는다.
 *
 * 데이터 오류가 아닌 실패(DB 오류 등)는 그대로 올려 단계를 실패시킨다. 청크마다 커밋하므로 그때까지 저장한 청크는
 * 남지만, 요약은 (영업일, 캠페인) 기준 upsert 라 같은 영업일에 다시 돌아도 결과가 같다(멱등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignStoreSummaryService {

    // store 의 updateAll 이 안에서 나누는 묶음(500)과 맞춘다.
    private static final int CHUNK_SIZE = 500;

    private final CampaignRepository campaignRepository;
    private final StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;
    private final TransactionTemplate transactionTemplate;

    // 집행 기간이 끝나지 않은 ACTIVE 캠페인의 요약을 businessDate 몫으로 만든다. 만든 개수를 돌려준다(데이터 오류로 건너뛴 것은 뺀다).
    public int prepare(LocalDate businessDate) {
        int prepared = 0;
        long lastId = 0L;
        List<CampaignSummaryRow> rows =
                campaignRepository.findActiveSummaryRowsAfter(lastId, businessDate, PageRequest.ofSize(CHUNK_SIZE));
        while (!rows.isEmpty()) {
            prepared += saveChunk(rows, businessDate);
            lastId = rows.get(rows.size() - 1).campaignId();
            rows = campaignRepository.findActiveSummaryRowsAfter(lastId, businessDate, PageRequest.ofSize(CHUNK_SIZE));
        }
        return prepared;
    }

    // 청크를 한 번에 저장한다. 데이터 오류로 실패하면 그 청크만 캠페인 한 건씩 다시 저장한다.
    private int saveChunk(List<CampaignSummaryRow> rows, LocalDate businessDate) {
        List<StoreCampaignSummaryUpdate> updates = toUpdates(rows, businessDate);
        try {
            transactionTemplate.executeWithoutResult(status -> storeCampaignSummaryUpdater.updateAll(updates));
            return updates.size();
        } catch (IllegalArgumentException e) {
            log.warn("event=CAMPAIGN_DAILY_SUMMARY_CHUNK_RETRY reason=DATA_ERROR businessDate={} chunkSize={} detail={}",
                    businessDate, updates.size(), e.getMessage());
            return saveOneByOne(updates, businessDate);
        }
    }

    private int saveOneByOne(List<StoreCampaignSummaryUpdate> updates, LocalDate businessDate) {
        int saved = 0;
        for (StoreCampaignSummaryUpdate update : updates) {
            try {
                transactionTemplate.executeWithoutResult(
                        status -> storeCampaignSummaryUpdater.updateAll(List.of(update)));
                saved++;
            } catch (IllegalArgumentException e) {
                log.error("event=CAMPAIGN_DAILY_SUMMARY_SKIPPED reason=DATA_ERROR campaignId={} storeId={} businessDate={} detail={}",
                        update.campaignId(), update.storeId(), businessDate, e.getMessage());
            }
        }
        return saved;
    }

    private List<StoreCampaignSummaryUpdate> toUpdates(List<CampaignSummaryRow> rows, LocalDate businessDate) {
        return rows.stream()
                .map(row -> new StoreCampaignSummaryUpdate(row.storeId(), row.campaignId(),
                        CampaignStatusService.toSummaryCommand(row, businessDate)))
                .toList();
    }
}
