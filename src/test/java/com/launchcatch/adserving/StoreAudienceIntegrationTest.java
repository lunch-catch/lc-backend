package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.feed.job.StoreAudienceJob;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.store.contract.StoreAdCandidateInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 00:00 묶음의 대상 인원 집계 단계(DailyJob.run)를 입구로 MySQL 의 feed_visit_daily 를 읽어 캠페인 계약에 넘기는 칸까지 본다(#592).
 * 접속 위치를 영업일 범위로 읽는 쿼리는 직접 쓴 SQL 이라 여기서만 실행된다. 캠페인, 가게, 회원은 contract 를 목으로 둔다.
 */
@DisplayName("가게별 대상 인원 집계 통합")
class StoreAudienceIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired StoreAudienceJob job;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM feed_visit_daily");
    }

    private void visit(LocalDate date, long memberId, String lat, String lng) {
        jdbcTemplate.update("""
                INSERT INTO feed_visit_daily (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(6), NOW(6))
                """, date, memberId, new BigDecimal(lat), new BigDecimal(lng));
    }

    private static MemberInfo member(long id, Gender gender) {
        return new MemberInfo(id, "회원" + id, MemberStatus.ACTIVE, true, false, true, null, null, gender,
                com.launchcatch.member.contract.AgeGroup.AGE_20S);
    }

    @SuppressWarnings("unchecked")
    private List<StoreAudienceCell> overwritten() {
        ArgumentCaptor<List<StoreAudienceCell>> captor = ArgumentCaptor.forClass(List.class);
        verify(storeAudienceUpdater, atLeastOnce()).overwrite(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private static int count(List<StoreAudienceCell> cells, int radius, TargetGender gender) {
        return cells.stream().filter(c -> c.targetRadius() == radius && c.gender() == gender && c.ageGroup() == AgeGroup.AGE_20S)
                .mapToInt(StoreAudienceCell::memberCount).findFirst().orElseThrow();
    }

    @Test
    void 최근_7일_접속만_회원마다_한_번_세고_OTHER_는_ALL_칸에만_넘긴다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(new CampaignInfo(101L, 1L, 55L,
                CampaignStatus.ACTIVE, 30, LocalTime.of(12, 0), LocalTime.of(14, 0), TODAY.minusDays(3), TODAY.plusDays(3),
                DiscountTargetType.ALL, DiscountType.PERCENT, 20, null, 1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                LocalDateTime.of(2026, 9, 1, 9, 0), null, LocalDateTime.of(2026, 10, 19, 23, 0), null, 10_000L)));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(new StoreAdCandidateInfo(55L,
                "점심식당", "KOREAN", null, new BigDecimal("37.4979000"), new BigDecimal("127.0276000"))));
        when(memberQueryService.findAllById(anyList())).thenReturn(List.of(member(1L, Gender.MALE), member(2L, Gender.OTHER),
                member(3L, Gender.FEMALE)));
        // 회원 1 은 사흘 접속했다. 회원 2 는 7일 전, 회원 3 은 오늘과 8일 전만 접속해 세지 않는다
        visit(TODAY.minusDays(1), 1L, "37.498", "127.028");
        visit(TODAY.minusDays(2), 1L, "37.498", "127.028");
        visit(TODAY.minusDays(3), 1L, "37.505", "127.028");
        visit(TODAY.minusDays(7), 2L, "37.498", "127.028");
        visit(TODAY, 3L, "37.498", "127.028");
        visit(TODAY.minusDays(8), 3L, "37.498", "127.028");

        job.run(TODAY);

        List<StoreAudienceCell> cells = overwritten();
        assertThat(cells).hasSize(48).allMatch(c -> c.storeId() == 55L && c.businessDate().equals(TODAY));
        assertThat(count(cells, 500, TargetGender.MALE)).isEqualTo(1);
        assertThat(count(cells, 500, TargetGender.FEMALE)).isZero();
        assertThat(count(cells, 500, TargetGender.ALL)).isEqualTo(2);
        assertThat(count(cells, 3000, TargetGender.ALL)).isEqualTo(2);
    }
}
