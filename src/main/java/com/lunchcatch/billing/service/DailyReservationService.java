package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.CampaignSpend;
import com.lunchcatch.billing.entity.BudgetDailyPlan;
import com.lunchcatch.billing.entity.PointLedger;
import com.lunchcatch.billing.repository.BudgetDailyPlanRepository;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.campaign.contract.CampaignInfo;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.ops.contract.PlatformSettingQueryService;
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
 *
 * 계획은 중단으로 만들어지고, 모든 점주의 예약이 끝난 뒤 그날 계획 전부의 상태를 캠페인에 맞춰 확인한다. 대상은 이번 실행이
 * 예약한 캠페인이 아니라 그날 계획이 있는 캠페인 전부다. 앞선 실행이 확인 전에 실패했고 그 사이 캠페인이 관리자 중단이나
 * 종료로 예약 대상에서 빠졌어도, 다음 실행이 그 계획을 확인해 중단으로 남긴다.
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
    private final DailyReleaseMarker releaseMarker;

    /*
     * 그날 예약을 마치고 대상 캠페인 수를 돌려준다.
     *
     * 단가를 먼저 정한다(unitPriceOf). 키가 없거나 0 이하이면 아무것도 쓰지 않고 예외로 단계를 실패시킨다. 0 으로 진행하면
     * 계획 행을 만들 수 없고, 잘못된 값으로 진행하면 모든 캠페인이 NO_POINTS 로 판정될 수 있다.
     * 보정 RELEASE 가 실패하면 예약을 진행하지 않는다. RESERVE 가 쓰는 잔액은 보정 뒤의 잔액이어야 한다.
     * 해제가 끝난 날짜는 오늘 예약 결과를 기다리지 않고 바로 해제 완료로 표시한다(BILLING_DAILY_RELEASE).
     */
    public int reserve(LocalDate businessDate) {
        long unitPrice = unitPriceOf(businessDate);
        LocalDate yesterday = businessDate.minusDays(1);
        boolean yesterdayMarked = false;
        for (LocalDate unreleasedDate : planRepository.findUnreleasedBusinessDatesBefore(businessDate)) {
            release(unreleasedDate);
            releaseMarker.mark(unreleasedDate);
            yesterdayMarked |= unreleasedDate.equals(yesterday);
        }
        // 전날은 남은 해제액이 0 이라 위 목록에 없을 수 있다. 오늘 예약의 성공과 무관하게 해제가 끝난 시점에 표시한다
        if (!yesterdayMarked) {
            releaseMarker.mark(yesterday);
        }

        List<CampaignInfo> campaigns = campaignQuery.findServableOn(businessDate);
        Map<Long, List<CampaignInfo>> byOwner = new TreeMap<>();
        for (CampaignInfo campaign : campaigns) {
            byOwner.computeIfAbsent(campaign.ownerId(), ownerId -> new ArrayList<>()).add(campaign);
        }
        byOwner.forEach((ownerId, owned) -> {
            owned.sort(Comparator.comparing(CampaignInfo::campaignId));
            recorder.reserveForOwner(ownerId, owned, businessDate, unitPrice);
        });

        // 예약이 모두 커밋된 뒤에 확인해야 캠페인을 읽은 뒤 계획을 만들기 전에 커밋된 중단을 놓치지 않는다
        confirmPlans(businessDate);
        mirror(businessDate, campaigns.stream().map(CampaignInfo::campaignId).toList());
        log.info("event=BILLING_DAILY_RESERVE_DONE businessDate={} campaignCount={} ownerCount={} unitPrice={}",
                businessDate, campaigns.size(), byOwner.size(), unitPrice);
        return campaigns.size();
    }

    /*
     * 그 영업일의 노출 단가다. 그날 계획이 이미 있으면 거기에 저장된 단가를 쓰고, 없을 때만 운영 설정(platform_setting)을
     * 읽는다.
     *
     * 단가는 00:00 에 읽은 값을 계획에 박아 그날 하루 고정하고, 바꾼 값은 다음 영업일부터 적용한다(배치 설계 「단가와 하루
     * 예산 하한의 출처」). 일부 점주만 예약하고 실패한 뒤 설정을 바꿔 재실행하면, 설정을 다시 읽을 경우 같은 날에 두 단가가
     * 섞인다. 저장된 단가가 둘 이상이면 이미 어긋난 것이라 예외로 멈춘다.
     */
    private long unitPriceOf(LocalDate businessDate) {
        List<Long> stored = planRepository.findDistinctUnitPricesOn(businessDate);
        if (stored.size() > 1) {
            throw new IllegalStateException("한 영업일에 노출 단가가 여럿 저장돼 있습니다. businessDate="
                    + businessDate + " unitPrices=" + stored);
        }
        if (stored.size() == 1) {
            return stored.get(0);
        }
        int unitPrice = platformSetting.getImpressionUnitPrice();
        if (unitPrice <= 0) {
            throw new IllegalStateException("노출 단가는 0보다 커야 합니다: " + unitPrice);
        }
        return unitPrice;
    }

    /*
     * 그 영업일 계획의 남은 몫을 RELEASE 한다. 그날 예약액에서 그날 DEDUCT 합을 뺀 값이 양수이고 RELEASE 가 아직
     * 없는 캠페인만 대상이다. 음수(예약액을 넘은 과금, 알려진 한계 L-2와 L-4)는 기록하지 않고 대사가 불일치로 남긴다.
     *
     * 00:00 예약(reserve)이 전날을 해제할 때 쓴다. 서빙이 하루 종일이라 이 해제가 전날의 정식 해제다.
     *
     * 예약액은 원장 RESERVE 합이 아니라 계획 행의 daily_budget 에서 읽는다. 예약과 충전 직후 재예약이 둘을 같은
     * 트랜잭션에서 함께 쓰므로 값이 같고, 원장에는 영업일만으로 찾는 인덱스가 없다.
     */
    public void release(LocalDate businessDate) {
        List<BudgetDailyPlan> plans = planRepository.findByBusinessDate(businessDate);
        if (plans.isEmpty()) {
            return;
        }
        // 점주 ID 순으로 묶되 해제액은 여기서 계산하지 않는다. 충전 재예약과 경합해도 최신 계획을
        // 기준으로 계산하려면 계정 행 잠금 뒤에 읽어야 한다.
        Map<Long, List<Long>> campaignsByOwner = new TreeMap<>();
        for (BudgetDailyPlan plan : plans) {
            campaignsByOwner.computeIfAbsent(plan.getOwnerId(), ownerId -> new ArrayList<>())
                    .add(plan.getCampaignId());
        }
        int campaigns = 0;
        for (Map.Entry<Long, List<Long>> entry : campaignsByOwner.entrySet()) {
            recorder.releaseForOwner(entry.getKey(), businessDate, entry.getValue());
            campaigns += entry.getValue().size();
        }
        if (campaigns > 0) {
            log.info("event=BILLING_RESERVE_LEFTOVER_RELEASED businessDate={} ownerCount={} campaignCount={}",
                    businessDate, campaignsByOwner.size(), campaigns);
        }
    }

    /*
     * 그날 계획 전부의 중단 표시를 캠페인의 현재 상태로 맞춘다. 캠페인 조회 한 번과 트랜잭션 하나가 MIRROR_CHUNK 개씩이다.
     * 실패하면 예외를 그대로 올린다. 확인되지 않은 계획은 중단으로 남아 과금되지 않고, 재실행이 다시 확인한다.
     */
    private void confirmPlans(LocalDate businessDate) {
        List<Long> campaignIds = planRepository.findByBusinessDate(businessDate).stream()
                .map(BudgetDailyPlan::getCampaignId)
                .sorted()
                .toList();
        int changed = 0;
        for (int from = 0; from < campaignIds.size(); from += MIRROR_CHUNK) {
            List<Long> chunk = campaignIds.subList(from, Math.min(from + MIRROR_CHUNK, campaignIds.size()));
            changed += recorder.confirmStatus(chunk, businessDate);
        }
        log.info("event=BILLING_RESERVE_STATUS_CONFIRMED businessDate={} planCount={} changedCount={}",
                businessDate, campaignIds.size(), changed);
    }

    /*
     * 커밋된 그날 계획으로 당일 소진 해시를 만든다. 이미 있는 해시는 덮어쓰지 않는다.
     * 실패해도 단계는 성공으로 둔다. 노출 차감과 진행률 조회가 해시가 없으면 계획 행으로 다시 만들기 때문이다.
     */
    private void mirror(LocalDate businessDate, List<Long> campaignIds) {
        for (int from = 0; from < campaignIds.size(); from += MIRROR_CHUNK) {
            List<Long> chunk = campaignIds.subList(from, Math.min(from + MIRROR_CHUNK, campaignIds.size()));
            try {
                hashRebuild.rebuildAllIfAbsent(businessDate, chunk);
            } catch (DataAccessException e) {
                log.warn("event=BILLING_RESERVE_HASH_MIRROR_FAILED businessDate={} campaignCount={}",
                        businessDate, chunk.size(), e);
            }
        }
    }
}
