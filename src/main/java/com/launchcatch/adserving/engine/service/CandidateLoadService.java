package com.launchcatch.adserving.engine.service;

import com.launchcatch.adserving.engine.entity.CandidateCampaignState;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.engine.repository.CandidateRow;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.store.contract.StoreAdCandidateInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 00:00 묶음에서 그날의 피드 후보 스냅샷을 만든다(FR-099 (8), FR-093). 서빙은 이 스냅샷만 읽고 다른 도메인 테이블을 읽지 않는다.
 * 적재와 7일 지난 행 지우기는 한 트랜잭션이고, 적재 중에 커밋된 상태 전이는 커밋 뒤 새 트랜잭션의 재조회로 반영한다(#582).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateLoadService {

    // 지난 후보를 남기는 영업일 수다. 영업일 D 의 적재가 D-7 보다 앞선 행을 지운다(#582)
    static final int RETENTION_DAYS = 7;

    // 가게가 없거나 메뉴 할인의 메뉴명을 찾지 못해 적재하지 못한 캠페인 수다. reason 태그로 가른다
    static final String SKIPPED_METRIC = "adserving_candidate_load_skipped_total";

    private final CampaignQueryService campaignQueryService;
    private final StoreQueryService storeQueryService;
    private final AdCandidateRepository adCandidateRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /*
     * 그날 서빙 대상을 읽어 후보로 넣고, 넣은 캠페인과 그때 읽은 상태 전이 시각을 돌려준다. 재조회가 이 시각과 견준다.
     * 같은 영업일로 다시 불려도 행이 늘지 않고 소진 시각을 덮지 않으며 상태는 늦을 때만 바꾼다.
     */
    @Transactional
    public Map<Long, LocalDateTime> load(LocalDate businessDate) {
        // 원천을 읽기 전에 잡는다. 늦게 커밋된 이전 적재가 이 적재의 값을 되돌리지 못하게 하는 기준이다
        LocalDateTime readAt = LocalDateTime.now(clock);
        List<CampaignInfo> campaigns = campaignQueryService.findServableOn(businessDate);
        Map<Long, StoreAdCandidateInfo> stores = readStores(campaigns);
        Map<Long, String> menuNames = readMenuNames(campaigns);

        List<CandidateRow> rows = new ArrayList<>(campaigns.size());
        Map<Long, LocalDateTime> loaded = new HashMap<>();
        for (CampaignInfo campaign : campaigns) {
            StoreAdCandidateInfo store = stores.get(campaign.storeId());
            if (store == null) {
                skip("store_missing");
                continue;
            }
            String menuName = null;
            if (campaign.discountTargetType() == DiscountTargetType.MENU) {
                menuName = menuNames.get(campaign.targetMenuId());
                // MENU 할인에 메뉴명이 없으면 CHECK chk_candidate_discount_menu 에 걸려 적재 전체가 실패한다
                if (menuName == null) {
                    skip("menu_missing");
                    continue;
                }
            }
            rows.add(toRow(businessDate, campaign, store, menuName));
            loaded.put(campaign.campaignId(), campaign.statusChangedAt());
        }
        adCandidateRepository.upsertAll(rows, readAt, LocalDateTime.now(clock));
        adCandidateRepository.deleteByBusinessDateBefore(businessDate.minusDays(RETENTION_DAYS));
        log.info("event=ADSERVING_CANDIDATE_LOADED businessDate={} campaignCount={} loadedCount={}", businessDate, campaigns.size(), rows.size());
        return loaded;
    }

    /*
     * 적재 중에 상태가 바뀐 캠페인을 반영한다. 그 사이 온 상태 통보는 행이 없어 반영되지 않았다.
     * 적재 트랜잭션 안에서 다시 읽으면 반복 읽기의 스냅샷 때문에 그 사이 커밋이 보이지 않아 커밋 뒤 따로 부른다.
     */
    @Transactional
    public void reconcile(LocalDate businessDate, Map<Long, LocalDateTime> loaded) {
        if (loaded.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (CampaignInfo campaign : campaignQueryService.findAllById(List.copyOf(loaded.keySet()))) {
            LocalDateTime changedAt = campaign.statusChangedAt();
            LocalDateTime before = loaded.get(campaign.campaignId());
            if (changedAt != null && (before == null || changedAt.isAfter(before))) {
                adCandidateRepository.changeState(businessDate, campaign.campaignId(), stateOf(campaign).name(), changedAt, now);
            }
        }
    }

    // 가게 계약의 상한 단위로 나눠 읽는다. 등록 전이거나 좌표가 없는 가게는 결과에 없다
    private Map<Long, StoreAdCandidateInfo> readStores(List<CampaignInfo> campaigns) {
        List<Long> storeIds = campaigns.stream().map(CampaignInfo::storeId).distinct().toList();
        Map<Long, StoreAdCandidateInfo> stores = new HashMap<>();
        for (int from = 0; from < storeIds.size(); from += StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS) {
            List<Long> chunk = storeIds.subList(from, Math.min(from + StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS, storeIds.size()));
            storeQueryService.findAdCandidatesByStoreIds(chunk).forEach(s -> stores.put(s.storeId(), s));
        }
        return stores;
    }

    private Map<Long, String> readMenuNames(List<CampaignInfo> campaigns) {
        List<Long> menuIds = campaigns.stream()
                .filter(c -> c.discountTargetType() == DiscountTargetType.MENU && c.targetMenuId() != null)
                .map(CampaignInfo::targetMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            return Map.of();
        }
        return storeQueryService.findMenusByIdIn(menuIds).menus().stream()
                .collect(Collectors.toMap(StoreMenuInfo::menuId, StoreMenuInfo::menuName));
    }

    private static CandidateRow toRow(LocalDate businessDate, CampaignInfo c, StoreAdCandidateInfo store, String menuName) {
        String ageGroups = c.targetAgeGroups().stream().map(AgeGroup::name).collect(Collectors.joining(","));
        return new CandidateRow(businessDate, c.campaignId(), c.storeId(), store.storeName(), store.storeImageUrl(),
                store.categoryCode(), store.latitude(), store.longitude(), c.targetRadius(), c.targetGender().name(),
                ageGroups, c.discountTargetType().name(), c.discountType().name(), c.discountValue(), menuName,
                c.usableStartTime(), c.usableEndTime(), c.issueQuantity(), c.createdAt(), c.newPriorityUntil(),
                stateOf(c), c.statusChangedAt());
    }

    // ACTIVE 만 서빙한다. 예약이 있는 PAUSED 와 재조회에서 만난 다른 상태는 PAUSED 다(#565 와 같다)
    private static CandidateCampaignState stateOf(CampaignInfo campaign) {
        return campaign.status() == CampaignStatus.ACTIVE ? CandidateCampaignState.SERVABLE : CandidateCampaignState.PAUSED;
    }

    private void skip(String reason) {
        meterRegistry.counter(SKIPPED_METRIC, "reason", reason).increment();
    }
}
