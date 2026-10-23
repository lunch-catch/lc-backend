package com.launchcatch.adserving.engine.service;

import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.engine.repository.CandidateBudget;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 그날 후보의 예약액과 단가를 정산에서 읽어 후보에 둔다(#837). 피드는 이 값과 자기 유효 노출 수로 예산 규칙을 판정해 요청마다 정산을 부르지 않는다.
 * 00:00 후보 적재 끝과 10분마다 부른다. 충전 재예약으로 장중에 늘어난 예약액은 다음 부름에서 반영된다.
 * 정산이 그날 계획을 돌려주지 않은 캠페인은 0 으로 둔다. 노출 상한에 걸려 피드에서 빠진다.
 *
 * 정산을 읽지 못하면 값을 바꾸지 않고 세며 예외를 올리지 않는다. 장애 처리 가이드의 기본 처리(오류로 올린다)에서 벗어난다.
 * 적재 단계를 실패시키면 재시도까지 실패할 때 그날 피드가 비고 뒤 단계도 돌지 않는다. 값이 비어 있는 동안 피드는 예산 규칙 없이 나간다(#307, #837).
 * 정산을 읽는 동안 DB 트랜잭션을 잡지 않는다. 행마다 같은 값을 덮어써 다시 불려도 결과가 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateBudgetService {

    // 정산에서 예산을 읽지 못한 횟수이고 경보는 이 지표에 건다
    static final String BUDGET_UNAVAILABLE_METRIC = "adserving_budget_unavailable_total";

    private final AdCandidateRepository adCandidateRepository;
    private final BudgetQueryService budgetQueryService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 갱신한 후보 수를 돌려주고 정산을 읽지 못하면 0 이다
    public int refresh(LocalDate businessDate) {
        List<Long> campaignIds = adCandidateRepository.findCampaignIdsByBusinessDate(businessDate);
        if (campaignIds.isEmpty()) {
            return 0;
        }
        Map<Long, BudgetProgress> found;
        try {
            found = read(campaignIds, businessDate);
        } catch (RuntimeException e) {
            // 원인이 캠페인이 아니라 정산이라 ID 대신 개수만 남긴다(#778)
            log.warn("event=ADSERVING_BUDGET_REFRESH_FAILED businessDate={} campaignCount={} fallback=KEEP_PREVIOUS",
                    businessDate, campaignIds.size(), e);
            meterRegistry.counter(BUDGET_UNAVAILABLE_METRIC).increment();
            return 0;
        }
        List<CandidateBudget> budgets = campaignIds.stream().map(id -> {
            BudgetProgress p = found.get(id);
            return p == null ? new CandidateBudget(id, 0L, 0L) : new CandidateBudget(id, p.reservedAmount(), p.unitPrice());
        }).toList();
        adCandidateRepository.updateBudgets(businessDate, budgets, LocalDateTime.now(clock));
        return budgets.size();
    }

    // 정산이 한 번에 받는 MAX_CAMPAIGN_IDS 개보다 많으면 나눠 부른다
    private Map<Long, BudgetProgress> read(List<Long> campaignIds, LocalDate businessDate) {
        int chunk = BudgetQueryService.MAX_CAMPAIGN_IDS;
        Map<Long, BudgetProgress> found = new HashMap<>();
        for (int from = 0; from < campaignIds.size(); from += chunk) {
            budgetQueryService.getProgress(campaignIds.subList(from, Math.min(from + chunk, campaignIds.size())), businessDate)
                    .forEach(p -> found.put(p.campaignId(), p));
        }
        return found;
    }
}
