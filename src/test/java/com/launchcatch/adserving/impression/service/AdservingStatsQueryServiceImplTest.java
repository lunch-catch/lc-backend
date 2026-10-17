package com.launchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.adserving.contract.CampaignMemberConcentration;
import com.launchcatch.adserving.contract.MemberInvalidImpressionCount;
import com.launchcatch.adserving.feed.repository.CampaignWishCount;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository.CampaignCounts;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository.MemberConcentration;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository.MemberInvalidCounts;
import java.time.LocalDate;
import java.util.List;
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
                counts(101L, 9, 1, 2, 3, 2, 3, 4, 4, 5),
                counts(103L, 1, 0, 0, 0, 1, 0, 0, 0, 1)));
        when(swipeLogRepository.countWishesByCampaign(DATE)).thenReturn(List.of(
                new CampaignWishCount(102L, 4),
                new CampaignWishCount(101L, 2)));

        List<CampaignImpressionStats> result = service.campaignStats(DATE);

        assertThat(result).containsExactly(
                new CampaignImpressionStats(101L, 9, 1, 2, 3, 2, 3, 4, 4, 5, 2),
                new CampaignImpressionStats(102L, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4),
                new CampaignImpressionStats(103L, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0));
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

    private static CampaignCounts counts(long campaignId, long valid, long soldOut, long expiredOrUnknown,
                                         long notOwner, long hour10, long hour11, long hour12,
                                         long allocation, long relevance) {
        return new CampaignCounts() {
            public long getCampaignId() { return campaignId; }
            public long getValidCount() { return valid; }
            public long getSoldOutCount() { return soldOut; }
            public long getExpiredOrUnknownCount() { return expiredOrUnknown; }
            public long getNotOwnerCount() { return notOwner; }
            public long getHour10Count() { return hour10; }
            public long getHour11Count() { return hour11; }
            public long getHour12Count() { return hour12; }
            public long getAllocationCount() { return allocation; }
            public long getRelevanceCount() { return relevance; }
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
