package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.feed.StoreAudienceCounter;
import com.launchcatch.adserving.feed.StoreAudienceCounter.Profile;
import com.launchcatch.adserving.feed.StoreAudienceCounter.Store;
import com.launchcatch.adserving.feed.StoreAudienceCounter.VisitGrid;
import com.launchcatch.adserving.feed.repository.FeedVisitRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.StoreAudienceUpdater;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 00:00 묶음 (10)에서 가게별 반경 안의 대상 인원을 세어 캠페인에 넘긴다(FR-099 (10), FR-042). 캠페인은 이 값으로 추천 하루 예산을 계산한다.
 * 캠페인이 광고 서빙을 조회하면 순환이 생겨 광고 서빙이 캠페인의 명령 계약으로 넣어 준다(설계 문서 2.4).
 * 넘기는 호출마다 캠페인의 트랜잭션이라 도중에 실패하면 앞의 가게만 오늘 값이다. 칸의 영업일로 구별되고 단계를 실패로 남겨 다시 돌린다(#592).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreAudienceService {

    /*
     * 접속을 세는 날 수다.
     * 그날을 빼고 D-7 ~ D-1 이다.
     */
    static final int VISIT_DAYS = 7;

    private final CampaignQueryService campaignQueryService;
    private final StoreQueryService storeQueryService;
    private final MemberQueryService memberQueryService;
    private final FeedVisitRepository feedVisitRepository;
    private final StoreAudienceUpdater storeAudienceUpdater;

    /*
     * 넘긴 칸 수를 돌려준다.
     * 집계할 가게는 그날 서빙 대상 캠페인의 가게다(#592).
     */
    public int aggregate(LocalDate businessDate) {
        List<Long> storeIds = campaignQueryService.findServableOn(businessDate).stream()
                .map(CampaignInfo::storeId).distinct().toList();
        if (storeIds.isEmpty()) {
            return 0;
        }
        List<Store> stores = readStores(storeIds);
        VisitGrid grid = new VisitGrid();
        feedVisitRepository.forEachPointBetween(businessDate.minusDays(VISIT_DAYS), businessDate.minusDays(1),
                v -> grid.add(v.memberId(), v.latitude(), v.longitude()));
        Map<Long, Profile> profiles = readProfiles(List.copyOf(grid.memberIds()));

        List<StoreAudienceCell> cells = StoreAudienceCounter.count(businessDate, stores, grid, profiles);
        /*
         * 계약의 상한 안에서 가게 단위로 나눠 넘긴다. 넘기다 실패하면 앞서 넘긴 가게만 오늘 값이라,
         * 가게 하나의 칸이 두 호출로 갈리면 그 가게가 오늘 값과 전날 값으로 섞인다(#774).
         */
        int cellsPerCall = StoreAudienceUpdater.MAX_CELLS / StoreAudienceCounter.CELLS_PER_STORE * StoreAudienceCounter.CELLS_PER_STORE;
        for (int from = 0; from < cells.size(); from += cellsPerCall) {
            storeAudienceUpdater.overwrite(cells.subList(from, Math.min(from + cellsPerCall, cells.size())));
        }
        log.info("event=ADSERVING_STORE_AUDIENCE_OVERWRITTEN businessDate={} storeCount={} memberCount={} cellCount={}",
                businessDate, stores.size(), profiles.size(), cells.size());
        return cells.size();
    }

    /*
     * 가게 계약의 상한 단위로 나눠 읽는다.
     * 등록 전이거나 좌표가 없는 가게는 결과에 없다.
     */
    private List<Store> readStores(List<Long> storeIds) {
        List<Store> stores = new ArrayList<>(storeIds.size());
        for (int from = 0; from < storeIds.size(); from += StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS) {
            List<Long> chunk = storeIds.subList(from, Math.min(from + StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS, storeIds.size()));
            storeQueryService.findAdCandidatesByStoreIds(chunk).forEach(s ->
                    stores.add(new Store(s.storeId(), s.latitude().doubleValue(), s.longitude().doubleValue())));
        }
        return stores;
    }

    /*
     * 회원 계약의 상한 단위로 나눠 읽는다.
     * 성별이나 연령대가 없는 회원(프로필 없음)은 세지 않는다.
     */
    private Map<Long, Profile> readProfiles(List<Long> memberIds) {
        Map<Long, Profile> profiles = new HashMap<>();
        for (int from = 0; from < memberIds.size(); from += MemberQueryService.MAX_FIND_ALL_SIZE) {
            List<Long> chunk = memberIds.subList(from, Math.min(from + MemberQueryService.MAX_FIND_ALL_SIZE, memberIds.size()));
            for (MemberInfo member : memberQueryService.findAllById(chunk)) {
                if (member.gender() != null && member.ageGroup() != null) {
                    profiles.put(member.memberId(), new Profile(member.gender(), member.ageGroup()));
                }
            }
        }
        return profiles;
    }
}
