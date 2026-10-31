package com.lunchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.adserving.contract.AdservingStatsQueryService;
import com.lunchcatch.adserving.contract.CampaignImpressionStats;
import com.lunchcatch.adserving.contract.CampaignMemberConcentration;
import com.lunchcatch.adserving.contract.MemberInvalidImpressionCount;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 분석 배치가 부르는 AdservingStatsQueryService 를 contract 빈으로 불러 MySQL 에 직접 쓴 집계 쿼리를 실행한다.
 * 유효 노출은 정산의 과금 판정과 맞춰야 하는 숫자라 통합 테스트로 둔다. 쿼리는 게이트가 세지 않아 여기서만 실행된다.
 * 노출 행은 판정을 거치지 않고 넣는다. 판정은 ImpressionIntegrationTest 가 본다.
 */
@DisplayName("광고 서빙 집계 조회 통합")
class AdservingStatsQueryIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final long CAMPAIGN = 101L;
    private static final long OTHER_CAMPAIGN = 102L;

    @Autowired AdservingStatsQueryService adservingStatsQueryService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM impression_log");
        jdbcTemplate.update("DELETE FROM swipe_log");
    }

    @Test
    void 판정_사유_시간대_슬롯을_캠페인별로_세고_시간대_합과_슬롯_합이_유효_노출과_같다() {
        valid(CAMPAIGN, 3001L, "ALLOCATION", false, LocalTime.of(10, 5));
        valid(CAMPAIGN, 3001L, "RELEVANCE", false, LocalTime.of(11, 0));
        valid(CAMPAIGN, 3002L, "RELEVANCE", true, LocalTime.of(12, 59));
        invalid(CAMPAIGN, 3003L, "NOT_OWNER", "ALLOCATION", LocalTime.of(11, 30));
        invalid(CAMPAIGN, 3003L, "EXPIRED_OR_UNKNOWN", null, LocalTime.of(12, 0));
        invalid(CAMPAIGN, 3004L, "EXPIRED_OR_UNKNOWN", null, LocalTime.of(13, 40));
        valid(OTHER_CAMPAIGN, 3001L, "ALLOCATION", false, LocalTime.of(10, 30));
        insertImpression(CAMPAIGN, 3001L, DATE.minusDays(1), "VALID", null, "ALLOCATION", false, LocalTime.of(10, 0));

        List<CampaignImpressionStats> result = adservingStatsQueryService.campaignStats(DATE);

        assertThat(result).containsExactly(
                new CampaignImpressionStats(CAMPAIGN, 3, 1, 2, 1, hours(10, 1, 11, 1, 12, 1), Map.of("ALLOCATION", 1L, "RELEVANCE", 2L), 0),
                new CampaignImpressionStats(OTHER_CAMPAIGN, 1, 0, 0, 0, hours(10, 1), Map.of("ALLOCATION", 1L), 0));
        CampaignImpressionStats stats = result.getFirst();
        assertThat(stats.hourlyCounts().stream().mapToLong(Long::longValue).sum()).isEqualTo(stats.validCount());
        assertThat(stats.slotCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(stats.validCount());
    }

    @Test
    void 엔진이_새로_붙인_슬롯_이름표도_마이그레이션_없이_저장되고_그_이름표로_센다() {
        valid(CAMPAIGN, 3001L, "ALLOCATION", false, LocalTime.of(10, 5));
        valid(CAMPAIGN, 3002L, "NEW_STORE", false, LocalTime.of(10, 6));
        valid(CAMPAIGN, 3003L, "NEW_STORE", false, LocalTime.of(10, 7));

        CampaignImpressionStats stats = adservingStatsQueryService.campaignStats(DATE).getFirst();

        assertThat(stats.slotCounts()).containsExactlyInAnyOrderEntriesOf(Map.of("ALLOCATION", 1L, "NEW_STORE", 2L));
    }

    // 서빙 시간대가 없어 0시부터 23시까지 받은 시각의 시로 센다(#498)
    @Test
    void 받은_시각의_시를_0시부터_23시까지_그대로_센다() {
        valid(CAMPAIGN, 3001L, "ALLOCATION", false, LocalTime.of(0, 5));
        valid(CAMPAIGN, 3002L, "ALLOCATION", false, LocalTime.of(9, 30));
        valid(CAMPAIGN, 3003L, "ALLOCATION", false, LocalTime.of(13, 20));
        valid(CAMPAIGN, 3004L, "ALLOCATION", false, LocalTime.of(23, 59));

        assertThat(adservingStatsQueryService.campaignStats(DATE)).containsExactly(
                new CampaignImpressionStats(CAMPAIGN, 4, 0, 0, 0, hours(0, 1, 9, 1, 13, 1, 23, 1), Map.of("ALLOCATION", 4L), 0));
    }

    @Test
    void 그날_피드_카드의_찜만_세고_패스와_다른_날은_세지_않는다() {
        valid(CAMPAIGN, 3001L, "ALLOCATION", false, LocalTime.of(10, 5));
        swipe(CAMPAIGN, 3001L, DATE, "WISH");
        swipe(CAMPAIGN, 3002L, DATE, "WISH");
        swipe(CAMPAIGN, 3003L, DATE, "PASS");
        swipe(CAMPAIGN, 3004L, DATE.minusDays(1), "WISH");
        swipe(OTHER_CAMPAIGN, 3001L, DATE, "WISH");

        assertThat(adservingStatsQueryService.campaignStats(DATE)).containsExactly(
                new CampaignImpressionStats(CAMPAIGN, 1, 0, 0, 0, hours(10, 1), Map.of("ALLOCATION", 1L), 2),
                new CampaignImpressionStats(OTHER_CAMPAIGN, 0, 0, 0, 0, hours(), Map.of(), 1));
    }

    @Test
    void 무효_노출을_캠페인과_회원별로_사유마다_세고_유효_노출과_다른_날은_넣지_않는다() {
        invalid(CAMPAIGN, 3003L, "NOT_OWNER", "ALLOCATION", LocalTime.of(10, 10));
        invalid(CAMPAIGN, 3003L, "NOT_OWNER", "RELEVANCE", LocalTime.of(10, 20));
        invalid(CAMPAIGN, 3003L, "EXPIRED_OR_UNKNOWN", null, LocalTime.of(11, 0));
        invalid(OTHER_CAMPAIGN, 3001L, "EXPIRED_OR_UNKNOWN", null, LocalTime.of(11, 0));
        valid(CAMPAIGN, 3002L, "ALLOCATION", false, LocalTime.of(10, 5));
        insertImpression(CAMPAIGN, 3004L, DATE.minusDays(1), "INVALID", "NOT_OWNER", "ALLOCATION", false,
                LocalTime.of(10, 0));

        assertThat(adservingStatsQueryService.memberInvalidCounts(DATE)).containsExactly(
                new MemberInvalidImpressionCount(CAMPAIGN, 3003L, 1, 2),
                new MemberInvalidImpressionCount(OTHER_CAMPAIGN, 3001L, 1, 0));
    }

    @Test
    void 유효_노출이_많은_상위_회원의_합과_회원_수를_세고_무효_노출은_넣지_않는다() {
        for (int i = 0; i < 4; i++) {
            valid(CAMPAIGN, 3001L, "ALLOCATION", false, LocalTime.of(10, i));
        }
        for (int i = 0; i < 3; i++) {
            valid(CAMPAIGN, 3002L, "ALLOCATION", false, LocalTime.of(11, i));
            valid(CAMPAIGN, 3003L, "ALLOCATION", false, LocalTime.of(11, 10 + i));
        }
        valid(CAMPAIGN, 3004L, "RELEVANCE", true, LocalTime.of(12, 0));
        invalid(CAMPAIGN, 3005L, "NOT_OWNER", "ALLOCATION", LocalTime.of(12, 1));
        invalid(CAMPAIGN, 3005L, "NOT_OWNER", "ALLOCATION", LocalTime.of(12, 2));
        valid(OTHER_CAMPAIGN, 3001L, "ALLOCATION", false, LocalTime.of(10, 30));

        assertThat(adservingStatsQueryService.memberConcentrations(DATE, 2)).containsExactly(
                new CampaignMemberConcentration(CAMPAIGN, 4, 7),
                new CampaignMemberConcentration(OTHER_CAMPAIGN, 1, 1));
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

    private void valid(long campaignId, long memberId, String slotType, boolean soldOut, LocalTime receivedAt) {
        insertImpression(campaignId, memberId, DATE, "VALID", null, slotType, soldOut, receivedAt);
    }

    private void invalid(long campaignId, long memberId, String reason, String slotType, LocalTime receivedAt) {
        insertImpression(campaignId, memberId, DATE, "INVALID", reason, slotType, false, receivedAt);
    }

    private void insertImpression(long campaignId, long memberId, LocalDate businessDate, String validity,
                                  String reason, String slotType, boolean soldOut, LocalTime receivedAt) {
        Timestamp received = Timestamp.valueOf(LocalDateTime.of(businessDate, receivedAt));
        jdbcTemplate.update("""
                INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type,
                    slot_position, displayed_at, lat_rounded, lng_rounded, validity, invalid_reason, sold_out,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 37.498, 127.028, ?, ?, ?, ?, ?)
                """, newServeId(), memberId, businessDate, campaignId, slotType == null ? null : 55L, slotType,
                slotType == null ? null : 1, received, validity, reason, soldOut, received, received);
    }

    private void swipe(long campaignId, long memberId, LocalDate businessDate, String action) {
        Timestamp at = Timestamp.valueOf(businessDate.atTime(10, 0));
        jdbcTemplate.update("""
                INSERT INTO swipe_log (serve_id, member_id, business_date, campaign_id, category_code, action,
                    occurred_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'KOREAN', ?, ?, ?, ?)
                """, newServeId(), memberId, businessDate, campaignId, action, at, at, at);
    }

    private static byte[] newServeId() {
        UUID id = UUID.randomUUID();
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
