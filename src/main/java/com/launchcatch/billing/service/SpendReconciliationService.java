package com.launchcatch.billing.service;

import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.dto.CampaignCounterMismatch;
import com.launchcatch.billing.dto.CampaignOverspend;
import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import com.launchcatch.ops.contract.BatchExecutionRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 당일 소진 카운터와 원장 DEDUCT 합을 캠페인마다 견준다(CAMPAIGN_SPEND_VS_LEDGER, 배치 설계 I-05).
 * 원장과 카운터만 본다. 노출 로그와 판정 기록은 읽지 않는다.
 *
 * 대조는 00:00 전일 대조(reconcilePreviousDay) 하나다. 다르면 불일치를 기록만 한다. 그날이 끝나 카운터를 고쳐도
 * 쓰일 곳이 없어서 카운터를 다시 만들지 않고 resolution_type 도 비워 둔다. 서빙이 하루 종일이라 당일 대조는 두지 않는다.
 * 그래서 카운터가 원장보다 큰 차이(알려진 한계 L-1)는 그날 안에 고쳐지지 않는다.
 *
 * 해시가 없는 캠페인은 견줄 값이 없어 건너뛰고 WARN 으로 남긴다. Valkey 재시작이나 만료로 사라진 경우다.
 *
 * 원장 DEDUCT 합이 그날 예약액(계획의 daily_budget)을 넘었는지도 본다(CAMPAIGN_OVERSPEND). 해시가 원장으로 재구성되면
 * 카운터와 원장이 같아져 위 대조로는 초과 소진(알려진 한계 L-2, L-4)이 보이지 않는다. 원장에 기록된 과금이라 되돌리지 않고
 * 기록만 한다. 이 확인은 카운터가 필요 없어 해시가 없는 캠페인도 한다.
 *
 * 트랜잭션을 걸지 않는다. Valkey 카운터를 읽는 동안 DB 연결과 트랜잭션을 쥐고 있지 않게 하기 위해서다. 계획과 원장 합은
 * 저장소 호출마다 짧은 읽기로 읽고, 불일치 기록만 묶음마다 SpendMismatchRecorder 의 트랜잭션 하나로 쓴다. Valkey 는
 * DB 트랜잭션에 묶이지 않으므로 하나로 묶어도 카운터와 원장을 같은 시점에 읽는 효과는 없다. 차감은 노출을 받은 날로
 * 들어오므로 대상인 전날에는 00:00 뒤에 차감이 없어 읽는 사이에 값이 바뀌지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpendReconciliationService {

    // 한 번에 견주는 캠페인 수다. 카운터 읽기 한 번과 원장 그룹 합 한 번이 이 크기로 나간다
    static final int CHUNK = 500;

    private final BudgetDailyPlanRepository planRepository;
    private final PointLedgerRepository ledgerRepository;
    private final SpendMismatchRecorder mismatchRecorder;
    private final DailySpendCounter spendCounter;
    private final BatchExecutionQueryService batchExecutions;
    private final Clock clock;

    /*
     * runDate 의 전날을 대조하고 기록한 불일치 수를 돌려준다.
     * 불일치 행의 job_execution_id 는 이 대조를 돌린 작업 행(jobName, runDate)을 가리킨다.
     * 같은 날을 다시 대조해도 불일치 행은 종류와 캠페인마다 하나다.
     */
    public int reconcilePreviousDay(LocalDate runDate, String jobName) {
        LocalDate target = runDate.minusDays(1);
        Map<Long, Long> reservedByCampaign = new HashMap<>();
        for (BudgetDailyPlan plan : planRepository.findByBusinessDate(target)) {
            reservedByCampaign.put(plan.getCampaignId(), plan.getDailyBudget());
        }
        if (reservedByCampaign.isEmpty()) {
            return 0;
        }
        List<Long> campaignIds = reservedByCampaign.keySet().stream().sorted().toList();
        Long jobExecutionId = findRunId(runDate, jobName);
        LocalDateTime now = LocalDateTime.now(clock);
        int mismatches = 0;
        int missing = 0;
        for (int from = 0; from < campaignIds.size(); from += CHUNK) {
            List<Long> chunk = campaignIds.subList(from, Math.min(from + CHUNK, campaignIds.size()));
            Map<Long, DailySpend> counters = spendCounter.readAll(target, chunk);
            Map<Long, Long> ledgerSpends = ledgerSpends(chunk, target);
            List<CampaignOverspend> overspends = new ArrayList<>();
            List<CampaignCounterMismatch> counterMismatches = new ArrayList<>();
            for (Long campaignId : chunk) {
                long ledgerSpend = ledgerSpends.getOrDefault(campaignId, 0L);
                long reserved = reservedByCampaign.get(campaignId);
                if (ledgerSpend > reserved) {
                    overspends.add(new CampaignOverspend(campaignId, reserved, ledgerSpend));
                    log.warn("event=BILLING_SPEND_RECON_OVERSPEND_MISMATCH businessDate={} campaignId={} reserved={} ledger={}",
                            target, campaignId, reserved, ledgerSpend);
                }
                DailySpend counter = counters.get(campaignId);
                if (counter == null) {
                    missing++;
                    continue;
                }
                if (counter.spend() != ledgerSpend) {
                    counterMismatches.add(new CampaignCounterMismatch(campaignId, ledgerSpend, counter.spend()));
                    log.warn("event=BILLING_SPEND_RECON_MISMATCH businessDate={} campaignId={} ledger={} counter={}",
                            target, campaignId, ledgerSpend, counter.spend());
                }
            }
            if (!overspends.isEmpty() || !counterMismatches.isEmpty()) {
                mismatchRecorder.record(target, jobExecutionId, now, overspends, counterMismatches);
                mismatches += overspends.size() + counterMismatches.size();
            }
        }
        if (missing > 0) {
            log.warn("event=BILLING_SPEND_RECON_HASH_MISSING businessDate={} missingCount={}", target, missing);
        }
        if (mismatches > 0) {
            log.error("event=BILLING_SPEND_RECON_DONE businessDate={} campaignCount={} mismatchCount={}",
                    target, campaignIds.size(), mismatches);
        } else {
            log.info("event=BILLING_SPEND_RECON_DONE businessDate={} campaignCount={} mismatchCount={}",
                    target, campaignIds.size(), mismatches);
        }
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
