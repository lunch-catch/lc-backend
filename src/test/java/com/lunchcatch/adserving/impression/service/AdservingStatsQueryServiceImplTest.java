package com.lunchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.contract.CampaignImpressionStats;
import com.lunchcatch.adserving.contract.CampaignMemberConcentration;
import com.lunchcatch.adserving.contract.MemberInvalidImpressionCount;
import com.lunchcatch.adserving.feed.repository.CampaignWishCount;
import com.lunchcatch.adserving.feed.repository.SwipeLogRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.CampaignCounts;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.HourlyCounts;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.SlotCounts;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.MemberConcentration;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository.MemberInvalidCounts;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("광고 서빙 집계 조회")
class AdservingStatsQueryServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

    private final ImpressionLogRepository impressionLogRepository = mock(ImpressionLogRepository.class);
    private final SwipeLogRepository swipeLogRepository = mock(SwipeLogRepository.class);
    private final AdservingStatsQueryServiceImpl service =
            new AdservingStatsQueryServiceImpl(impressionLogRepository, swipeLogRepository);

    @Test
    void 노출과_찜을_캠페인_ID로_합치고_한쪽만_있는_캠페인은_다른_쪽을_0으로_채운다() {
        when(impressionLogRepository.countByCampaignOn(DATE)).thenReturn(List.of(
                counts(101L, 9, 1, 2, 3),
                counts(103L, 1, 0, 0, 0)));
        when(impressionLogRepository.countValidByCampaignAndSlotOn(DATE)).thenReturn(List.of(
                slot(101L, "ALLOCATION", 4), slot(101L, "RELEVANCE", 5), slot(103L, "RELEVANCE", 1)));
        when(impressionLogRepository.countValidByCampaignAndHourOn(DATE)).thenReturn(List.of(
                hourly(101L, 0, 1), hourly(101L, 11, 5), hourly(101L, 23, 3), hourly(103L, 10, 1)));
        when(swipeLogRepository.countWishesByCampaign(DATE)).thenReturn(List.of(
                new CampaignWishCount(102L, 4),
                new CampaignWishCount(101L, 2)));

        List<CampaignImpressionStats> result = service.campaignStats(DATE);

        assertThat(result).containsExactly(
                new CampaignImpressionStats(101L, 9, 1, 2, 3, hours(0, 1, 11, 5, 23, 3), Map.of("ALLOCATION", 4L, "RELEVANCE", 5L), 2),
                new CampaignImpressionStats(102L, 0, 0, 0, 0, hours(), Map.of(), 4),
                new CampaignImpressionStats(103L, 1, 0, 0, 0, hours(10, 1), Map.of("RELEVANCE", 1L), 0));
    }

    @Test
    void 시간대는_노출이_없는_시도_0으로_채워_항상_24칸이다() {
        when(impressionLogRepository.countByCampaignOn(DATE)).thenReturn(List.of(counts(101L, 2, 0, 0, 0)));
        when(impressionLogRepository.countValidByCampaignAndHourOn(DATE)).thenReturn(List.of(hourly(101L, 3, 2)));
        when(swipeLogRepository.countWishesByCampaign(DATE)).thenReturn(List.of());

        List<Long> hourlyCounts = service.campaignStats(DATE).get(0).hourlyCounts();

        assertThat(hourlyCounts).hasSize(24);
        assertThat(hourlyCounts.get(3)).isEqualTo(2L);
        assertThat(hourlyCounts.stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
    }

    @Test
    void 그날_노출도_찜도_없으면_빈_목록이다() {
        when(impressionLogRepository.countByCampaignOn(DATE)).thenReturn(List.of());
        when(swipeLogRepository.countWishesByCampaign(DATE)).thenReturn(List.of());

        assertThat(service.campaignStats(DATE)).isEmpty();
    }

    @Test
    void 회원별_무효_노출을_사유별_건수로_돌려준다() {
        when(impressionLogRepository.countInvalidByMemberOn(DATE)).thenReturn(List.of(
                invalid(101L, 3001L, 2, 0),
                invalid(101L, 3002L, 0, 5)));

        assertThat(service.memberInvalidCounts(DATE)).containsExactly(
                new MemberInvalidImpressionCount(101L, 3001L, 2, 0),
                new MemberInvalidImpressionCount(101L, 3002L, 0, 5));
    }

    @Test
    void 상위_회원_수를_그대로_넘겨_캠페인별_집중도를_돌려준다() {
        when(impressionLogRepository.concentrationByCampaignOn(DATE, 5)).thenReturn(List.of(
                concentration(101L, 7, 40)));

        assertThat(service.memberConcentrations(DATE, 5)).containsExactly(
                new CampaignMemberConcentration(101L, 7, 40));
    }

    @Test
    void 상위_회원_수가_1_미만이면_조회하지_않고_거부한다() {
        assertThatThrownBy(() -> service.memberConcentrations(DATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(impressionLogRepository);
    }

    // 시와 건수를 번갈아 받아 24칸을 채운다. 받지 않은 시는 0 이다
    private static List<Long> hours(int... hourAndCount) {
        Long[] counts = new Long[24];
        Arrays.fill(counts, 0L);
        for (int i = 0; i < hourAndCount.length; i += 2) {
            counts[hourAndCount[i]] = (long) hourAndCount[i + 1];
        }
        return List.of(counts);
    }

    private static HourlyCounts hourly(long campaignId, int hour, long valid) {
        return new HourlyCounts() {
            public long getCampaignId() { return campaignId; }
            public int getHour() { return hour; }
            public long getValidCount() { return valid; }
        };
    }

    private static CampaignCounts counts(long campaignId, long valid, long soldOut, long expiredOrUnknown,
                                         long notOwner) {
        return new CampaignCounts() {
            public long getCampaignId() { return campaignId; }
            public long getValidCount() { return valid; }
            public long getSoldOutCount() { return soldOut; }
            public long getExpiredOrUnknownCount() { return expiredOrUnknown; }
            public long getNotOwnerCount() { return notOwner; }
        };
    }

    private static SlotCounts slot(long campaignId, String slotType, long valid) {
        return new SlotCounts() {
            public long getCampaignId() { return campaignId; }
            public String getSlotType() { return slotType; }
            public long getValidCount() { return valid; }
        };
    }

    private static MemberInvalidCounts invalid(long campaignId, long memberId, long expiredOrUnknown, long notOwner) {
        return new MemberInvalidCounts() {
            public long getCampaignId() { return campaignId; }
            public long getMemberId() { return memberId; }
            public long getExpiredOrUnknownCount() { return expiredOrUnknown; }
            public long getNotOwnerCount() { return notOwner; }
        };
    }

    private static MemberConcentration concentration(long campaignId, long distinctMembers, long topMembersValid) {
        return new MemberConcentration() {
            public long getCampaignId() { return campaignId; }
            public long getDistinctMemberCount() { return distinctMembers; }
            public long getTopMembersValidCount() { return topMembersValid; }
        };
    }
}
