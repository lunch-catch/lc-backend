package com.lunchcatch.adserving.impression.service;

import com.lunchcatch.adserving.contract.AdservingStatsQueryService;
import com.lunchcatch.adserving.contract.CampaignImpressionStats;
import com.lunchcatch.adserving.contract.CampaignMemberConcentration;
import com.lunchcatch.adserving.contract.MemberInvalidImpressionCount;
import com.lunchcatch.adserving.feed.repository.CampaignWishCount;
import com.lunchcatch.adserving.feed.repository.SwipeLogRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.CampaignCounts;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.HourlyCounts;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.SlotCounts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 분석 배치가 영업일마다 부르는 집계 조회다. 읽기만 하므로 읽기 전용 트랜잭션 하나로 묶어 한 호출 안의 쿼리들이 같은 스냅샷을 본다.
 * 노출은 impression_log, 찜은 swipe_log 에서 따로 세어 캠페인 ID 로 합친다. 두 테이블을 조인하면 노출 행마다 찜이 곱해진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdservingStatsQueryServiceImpl implements AdservingStatsQueryService {

    private final ImpressionLogRepository impressionLogRepository;
    private final SwipeLogRepository swipeLogRepository;

    @Override
    public List<CampaignImpressionStats> campaignStats(LocalDate businessDate) {
        Map<Long, CampaignCounts> impressions = impressionLogRepository.countByCampaignOn(businessDate).stream()
                .collect(Collectors.toMap(CampaignCounts::getCampaignId, Function.identity()));
        Map<Long, Long[]> hourly = new HashMap<>();
        for (HourlyCounts h : impressionLogRepository.countValidByCampaignAndHourOn(businessDate)) {
            hourly.computeIfAbsent(h.getCampaignId(), id -> emptyHours())[h.getHour()] = h.getValidCount();
        }
        Map<Long, Map<String, Long>> slots = new HashMap<>();
        for (SlotCounts s : impressionLogRepository.countValidByCampaignAndSlotOn(businessDate)) {
            slots.computeIfAbsent(s.getCampaignId(), id -> new HashMap<>()).put(s.getSlotType(), s.getValidCount());
        }
        Map<Long, Long> wishes = swipeLogRepository.countWishesByCampaign(businessDate).stream()
                .collect(Collectors.toMap(CampaignWishCount::campaignId, CampaignWishCount::count));

        TreeSet<Long> campaignIds = new TreeSet<>(impressions.keySet());
        campaignIds.addAll(wishes.keySet());
        List<CampaignImpressionStats> result = new ArrayList<>(campaignIds.size());
        for (Long campaignId : campaignIds) {
            long wishCount = wishes.getOrDefault(campaignId, 0L);
            CampaignCounts c = impressions.get(campaignId);
            List<Long> hourlyCounts = List.of(hourly.getOrDefault(campaignId, emptyHours()));
            result.add(c == null
                    ? new CampaignImpressionStats(campaignId, 0, 0, 0, 0, hourlyCounts, Map.of(), wishCount)
                    : new CampaignImpressionStats(campaignId, c.getValidCount(), c.getSoldOutCount(),
                            c.getExpiredOrUnknownCount(), c.getNotOwnerCount(), hourlyCounts,
                            slots.getOrDefault(campaignId, Map.of()), wishCount));
        }
        return result;
    }

    // 시(0~23)를 인덱스로 하는 24칸이다. 노출이 없는 시는 0 이다
    private static Long[] emptyHours() {
        Long[] hours = new Long[24];
        Arrays.fill(hours, 0L);
        return hours;
    }

    @Override
    public List<MemberInvalidImpressionCount> memberInvalidCounts(LocalDate businessDate) {
        return impressionLogRepository.countInvalidByMemberOn(businessDate).stream()
                .map(r -> new MemberInvalidImpressionCount(r.getCampaignId(), r.getMemberId(),
                        r.getExpiredOrUnknownCount(), r.getNotOwnerCount()))
                .toList();
    }

    @Override
    public List<CampaignMemberConcentration> memberConcentrations(LocalDate businessDate, int topMembers) {
        if (topMembers < 1) {
            throw new IllegalArgumentException("상위 회원 수는 1 이상이어야 한다: " + topMembers);
        }
        return impressionLogRepository.concentrationByCampaignOn(businessDate, topMembers).stream()
                .map(r -> new CampaignMemberConcentration(r.getCampaignId(), r.getDistinctMemberCount(),
                        r.getTopMembersValidCount()))
                .toList();
    }
}
