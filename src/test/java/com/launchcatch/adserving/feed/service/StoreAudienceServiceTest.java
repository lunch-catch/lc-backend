package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.feed.repository.FeedVisitRepository;
import com.launchcatch.adserving.feed.repository.FeedVisitRepository.VisitPoint;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.StoreAudienceUpdater;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.store.contract.StoreAdCandidateInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("가게별 대상 인원 집계")
class StoreAudienceServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final CampaignQueryService campaignQueryService = mock(CampaignQueryService.class);
    private final StoreQueryService storeQueryService = mock(StoreQueryService.class);
    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final FeedVisitRepository feedVisitRepository = mock(FeedVisitRepository.class);
    private final StoreAudienceUpdater storeAudienceUpdater = mock(StoreAudienceUpdater.class);
    private final StoreAudienceService service = new StoreAudienceService(campaignQueryService, storeQueryService,
            memberQueryService, feedVisitRepository, storeAudienceUpdater);

    private static CampaignInfo campaign(long id, long storeId) {
        return new CampaignInfo(id, 1L, storeId, CampaignStatus.ACTIVE, 30, LocalTime.of(12, 0), LocalTime.of(14, 0),
                TODAY.minusDays(3), TODAY.plusDays(3), DiscountTargetType.ALL, DiscountType.PERCENT, 20, null, 1000,
                TargetGender.ALL, List.of(AgeGroup.AGE_20S), LocalDateTime.of(2026, 9, 1, 9, 0), null,
                LocalDateTime.of(2026, 10, 19, 23, 0), null, 10_000L);
    }

    private static StoreAdCandidateInfo store(long id) {
        return new StoreAdCandidateInfo(id, "가게" + id, "KOREAN", null, new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));
    }

    private static MemberInfo member(long id, Gender gender, com.launchcatch.member.contract.AgeGroup age) {
        return new MemberInfo(id, "회원" + id, MemberStatus.ACTIVE, true, false, true, null, null, gender, age);
    }

    @SuppressWarnings("unchecked")
    private List<StoreAudienceCell> overwritten() {
        ArgumentCaptor<List<StoreAudienceCell>> captor = ArgumentCaptor.forClass(List.class);
        verify(storeAudienceUpdater, org.mockito.Mockito.atLeastOnce()).overwrite(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private void givenStores() {
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(StoreAudienceServiceTest::store).toList();
        });
    }

    @Test
    void 그날_서빙_대상_캠페인의_가게마다_최근_7일_접속_회원을_세어_넘긴다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(campaign(101, 55), campaign(102, 55)));
        givenStores();
        when(feedVisitRepository.findPointsBetween(TODAY.minusDays(7), TODAY.minusDays(1))).thenReturn(List.of(
                new VisitPoint(1L, 37.4979, 127.0276), new VisitPoint(2L, 37.4979, 127.0276)));
        when(memberQueryService.findAllById(anyList())).thenReturn(List.of(
                member(1L, Gender.MALE, com.launchcatch.member.contract.AgeGroup.AGE_20S),
                member(2L, null, null)));

        assertThat(service.aggregate(TODAY)).isEqualTo(48);

        List<StoreAudienceCell> cells = overwritten();
        assertThat(cells).hasSize(48).allMatch(c -> c.storeId() == 55L && c.businessDate().equals(TODAY));
        assertThat(cells.stream().filter(c -> c.targetRadius() == 500 && c.gender() == TargetGender.ALL
                && c.ageGroup() == AgeGroup.AGE_20S).findFirst().orElseThrow().memberCount()).isEqualTo(1);
    }

    // 회원 계약은 한 번에 1,000명, 캠페인 계약은 한 번에 1,000칸까지다. 넘으면 거부한다
    @Test
    void 회원과_칸이_계약의_상한을_넘어도_나눠_읽고_나눠_넘긴다() {
        List<CampaignInfo> campaigns = LongStream.rangeClosed(1, 30).mapToObj(i -> campaign(i, i)).toList();
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(campaigns);
        givenStores();
        List<VisitPoint> visits = new ArrayList<>();
        LongStream.rangeClosed(1, MemberQueryService.MAX_FIND_ALL_SIZE + 1).forEach(i -> visits.add(new VisitPoint(i, 37.4979, 127.0276)));
        when(feedVisitRepository.findPointsBetween(any(), any())).thenReturn(visits);
        when(memberQueryService.findAllById(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            if (ids.size() > MemberQueryService.MAX_FIND_ALL_SIZE) {
                throw new IllegalArgumentException("회원이 상한을 넘었다: " + ids.size());
            }
            return ids.stream().map(id -> member(id, Gender.FEMALE, com.launchcatch.member.contract.AgeGroup.AGE_30S)).toList();
        });
        ArgumentCaptor<List<StoreAudienceCell>> captor = ArgumentCaptor.forClass(List.class);

        service.aggregate(TODAY);

        verify(storeAudienceUpdater, org.mockito.Mockito.times(2)).overwrite(captor.capture());
        assertThat(captor.getAllValues()).allMatch(chunk -> chunk.size() <= StoreAudienceUpdater.MAX_CELLS);
        assertThat(captor.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(30 * 48);
        assertThat(captor.getAllValues().stream().flatMap(List::stream)
                .filter(c -> c.storeId() == 1L && c.targetRadius() == 500 && c.gender() == TargetGender.FEMALE
                        && c.ageGroup() == AgeGroup.AGE_30S).findFirst().orElseThrow().memberCount())
                .isEqualTo(MemberQueryService.MAX_FIND_ALL_SIZE + 1);
    }

    @Test
    void 그날_서빙_대상이_없으면_넘기지_않는다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of());

        assertThat(service.aggregate(TODAY)).isZero();

        verify(storeAudienceUpdater, never()).overwrite(anyList());
    }

    // 실패를 숨기지 않고 단계를 실패로 남겨 묶음 규칙대로 다시 돌게 한다(#592)
    @Test
    void 넘기다_실패하면_예외를_올린다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(campaign(101, 55)));
        givenStores();
        when(feedVisitRepository.findPointsBetween(any(), any())).thenReturn(List.of());
        doThrow(new QueryTimeoutException("db")).when(storeAudienceUpdater).overwrite(anyList());

        assertThatThrownBy(() -> service.aggregate(TODAY)).isInstanceOf(QueryTimeoutException.class);
    }
}
