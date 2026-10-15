package com.launchcatch.billing.service;

import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 당일 소진 카운터와 원장 DEDUCT 합을 캠페인마다 견준다(CAMPAIGN_SPEND_VS_LEDGER, 배치 설계 I-05).
 * 원장과 카운터만 본다. 노출 로그와 판정 기록은 읽지 않는다.
 *
 * 00:00 전일 대조는 다르면 불일치를 기록만 한다. 그날 서빙이 끝나 카운터를 고쳐도 쓰일 곳이 없어서
 * 카운터를 다시 만들지 않고 resolution_type 도 비워 둔다. 당일 카운터의 재구성은 13:30 대사(구현 순서 11번)가 맡는다.
 *
 * 해시가 없는 캠페인은 견줄 값이 없어 건너뛰고 WARN 으로 남긴다. Valkey 재시작이나 만료로 사라진 경우다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpendReconciliationService {

    // 한 번에 견주는 캠페인 수다. 카운터 읽기 한 번과 원장 그룹 합 한 번이 이 크기로 나간다
    static final int CHUNK = 500;

    private final BudgetDailyPlanRepository planRepository;
    private final PointLedgerRepository ledgerRepository;
    private final SettlementMismatchRepository mismatchRepository;
    private final DailySpendCounter spendCounter;
    private final BatchExecutionQueryService batchExecutions;
    private final Clock clock;

    /*
     * runDate 의 전날을 대조하고 기록한 불일치 수를 돌려준다.
     * 불일치 행의 job_execution_id 는 이 대조를 돌린 작업 행(jobName, runDate)을 가리킨다.
     * 같은 날을 다시 대조해도 불일치 행은 캠페인마다 하나다.
     */
    @Transactional
    public int reconcilePreviousDay(LocalDate runDate, String jobName) {
        LocalDate target = runDate.minusDays(1);
        List<Long> campaignIds = planRepository.findByBusinessDate(target).stream()
                .map(BudgetDailyPlan::getCampaignId)
                .sorted()
                .toList();
        if (campaignIds.isEmpty()) {
            return 0;
        }
        Long jobExecutionId = findRunId(runDate, jobName);
        LocalDateTime now = LocalDateTime.now(clock);
        int mismatches = 0;
        int missing = 0;
        for (int from = 0; from < campaignIds.size(); from += CHUNK) {
            List<Long> chunk = campaignIds.subList(from, Math.min(from + CHUNK, campaignIds.size()));
            Map<Long, DailySpend> counters = spendCounter.readAll(target, chunk);
            Map<Long, Long> ledgerSpends = ledgerSpends(chunk, target);
            for (Long campaignId : chunk) {
                DailySpend counter = counters.get(campaignId);
                if (counter == null) {
                    missing++;
                    continue;
                }
                long ledgerSpend = ledgerSpends.getOrDefault(campaignId, 0L);
                if (counter.spend() != ledgerSpend) {
                    mismatchRepository.insertCampaignSpendMismatchIfAbsent(
                            target, campaignId, ledgerSpend, counter.spend(), jobExecutionId, now);
                    log.warn("event=SPEND_RECON_MISMATCH businessDate={} campaignId={} ledger={} counter={}",
                            target, campaignId, ledgerSpend, counter.spend());
                    mismatches++;
                }
            }
        }
        if (missing > 0) {
            log.warn("event=SPEND_RECON_HASH_MISSING businessDate={} missing={}", target, missing);
        }
        log.info("event=SPEND_RECON_DONE businessDate={} campaigns={} mismatches={}",
                target, campaignIds.size(), mismatches);
        return mismatches;
    }

    // 작업 행은 스케줄러가 단계를 시작할 때 먼저 만든다. 찾지 못하면 NULL 로 남긴다(소프트 참조)
    private Long findRunId(LocalDate runDate, String jobName) {
        return batchExecutions.findByBusinessDate(runDate).stream()
                .filter(record -> record.jobName().equals(jobName))
                .map(BatchExecutionRecord::runId)
                .findFirst()
                .orElse(null);
    }

    private Map<Long, Long> ledgerSpends(List<Long> campaignIds, LocalDate businessDate) {
        Map<Long, Long> spends = new HashMap<>();
        for (CampaignSpend spend : ledgerRepository.sumDeductForCampaignsOn(campaignIds, businessDate)) {
            spends.put(spend.campaignId(), spend.spend());
        }
        return spends;
    }
}
