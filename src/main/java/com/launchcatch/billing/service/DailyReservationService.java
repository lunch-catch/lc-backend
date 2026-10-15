package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.ops.contract.PlatformSettingQueryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * 00:00 예약 단계(V52 102행 (5), 배치 설계 I-02)다. 전날 남은 예약을 보정 RELEASE 하고, 그날 서빙할 캠페인마다
 * 예약액을 정해 RESERVE 와 그날 계획을 남긴 뒤 당일 소진 해시를 만든다.
 *
 * 트랜잭션을 걸지 않는다. 쓰기는 DailyReservationRecorder 가 점주 단위로 짧게 끊는다.
 *
 * 같은 영업일로 다시 불려도 결과가 같다. 이미 해제된 캠페인은 다시 해제하지 않고, 이미 계획이 있는 캠페인은 다시
 * 배분하지 않는다. 중간에 실패하면 끝난 점주의 결과는 남고 다음 실행이 나머지를 채운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReservationService {

    // 해시를 한 번에 만드는 캠페인 수다. 재구성 한 번이 계획 IN 조회와 원장 그룹 합을 이 크기로 낸다
    static final int MIRROR_CHUNK = 500;

    private final CampaignQueryService campaignQuery;
    private final PlatformSettingQueryService platformSetting;
    private final BudgetDailyPlanRepository planRepository;
    private final PointLedgerRepository ledgerRepository;
    private final DailyReservationRecorder recorder;
    private final SpendHashRebuildService hashRebuild;

    /*
     * 그날 예약을 마치고 대상 캠페인 수를 돌려준다.
     *
     * 단가를 먼저 읽는다. 키가 없거나 0 이하이면 아무것도 쓰지 않고 예외로 단계를 실패시킨다. 0 으로 진행하면 계획 행을
     * 만들 수 없고, 잘못된 값으로 진행하면 모든 캠페인이 NO_POINTS 로 판정될 수 있다.
     * 보정 RELEASE 가 실패하면 예약을 진행하지 않는다. RESERVE 가 쓰는 잔액은 보정 뒤의 잔액이어야 한다.
     */
    public int reserve(LocalDate businessDate) {
        long unitPrice = unitPrice();
        releaseLeftovers(businessDate.minusDays(1));

        List<CampaignInfo> campaigns = campaignQuery.findServableOn(businessDate);
        Map<Long, List<CampaignInfo>> byOwner = new TreeMap<>();
        for (CampaignInfo campaign : campaigns) {
            byOwner.computeIfAbsent(campaign.ownerId(), ownerId -> new ArrayList<>()).add(campaign);
        }
        byOwner.forEach((ownerId, owned) -> {
            owned.sort(Comparator.comparing(CampaignInfo::campaignId));
            recorder.reserveForOwner(ownerId, owned, businessDate, unitPrice);
        });

        mirror(businessDate, campaigns.stream().map(CampaignInfo::campaignId).toList());
        log.info("event=DAILY_RESERVE_DONE businessDate={} campaigns={} owners={} unitPrice={}",
                businessDate, campaigns.size(), byOwner.size(), unitPrice);
        return campaigns.size();
    }

    private long unitPrice() {
        int unitPrice = platformSetting.getImpressionUnitPrice();
        if (unitPrice <= 0) {
            throw new IllegalStateException("노출 단가는 0보다 커야 합니다: " + unitPrice);
        }
        return unitPrice;
    }

    /*
     * 전날 13:30 해제가 남긴 몫을 보정 RELEASE 한다. 그날 예약액에서 그날 DEDUCT 합을 뺀 값이 양수이고 RELEASE 가 아직
     * 없는 캠페인만 대상이다. 음수(예약액을 넘은 과금, 알려진 한계 L-2와 L-4)는 기록하지 않고 대사가 불일치로 남긴다.
     *
     * 예약액은 원장 RESERVE 합이 아니라 계획 행의 daily_budget 에서 읽는다. 예약과 충전 직후 재예약이 둘을 같은
     * 트랜잭션에서 함께 쓰므로 값이 같고, 원장에는 영업일만으로 찾는 인덱스가 없다.
     */
    private void releaseLeftovers(LocalDate previousDay) {
        List<BudgetDailyPlan> plans = planRepository.findByBusinessDate(previousDay);
        if (plans.isEmpty()) {
            return;
        }
        List<Long> campaignIds = plans.stream().map(BudgetDailyPlan::getCampaignId).toList();
        Set<Long> ownerIds = plans.stream().map(BudgetDailyPlan::getOwnerId).collect(Collectors.toSet());
        Set<Long> released = new HashSet<>(ledgerRepository.findReleasedCampaignIds(ownerIds, previousDay));
        Map<Long, Long> spends = new HashMap<>();
        for (CampaignSpend spend : ledgerRepository.sumDeductForCampaignsOn(campaignIds, previousDay)) {
            spends.put(spend.campaignId(), spend.spend());
        }
        List<PointLedger> releases = new ArrayList<>();
        for (BudgetDailyPlan plan : plans) {
            if (released.contains(plan.getCampaignId())) {
                continue;
            }
            long left = plan.getDailyBudget() - spends.getOrDefault(plan.getCampaignId(), 0L);
            if (left > 0) {
                releases.add(PointLedger.release(plan.getOwnerId(), plan.getCampaignId(), previousDay, left));
            }
        }
        if (!releases.isEmpty()) {
            recorder.release(releases);
            log.info("event=RESERVE_LEFTOVER_RELEASED businessDate={} campaigns={}", previousDay, releases.size());
        }
    }

    /*
     * 커밋된 그날 계획으로 당일 소진 해시를 만든다. 이미 있는 해시는 덮어쓰지 않는다.
     * 실패해도 단계는 성공으로 둔다. 노출 차감과 진행률 조회가 해시가 없으면 계획 행으로 다시 만들기 때문이다(E-6).
     */
    private void mirror(LocalDate businessDate, List<Long> campaignIds) {
        for (int from = 0; from < campaignIds.size(); from += MIRROR_CHUNK) {
            List<Long> chunk = campaignIds.subList(from, Math.min(from + MIRROR_CHUNK, campaignIds.size()));
            try {
                hashRebuild.rebuildAllIfAbsent(businessDate, chunk);
            } catch (DataAccessException e) {
                log.warn("event=RESERVE_HASH_MIRROR_FAILED businessDate={} campaigns={}",
                        businessDate, chunk.size(), e);
            }
        }
    }
}
