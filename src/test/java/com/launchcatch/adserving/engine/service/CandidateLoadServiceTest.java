package com.launchcatch.adserving.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.entity.CandidateCampaignState;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.engine.repository.CandidateRow;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.store.contract.StoreAdCandidateInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreMenuQueryResult;
import com.launchcatch.store.contract.StoreQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("그날 후보 적재")
class CandidateLoadServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 0, 0, 5);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 9, 30, 23, 0);

    private final CampaignQueryService campaignQueryService = mock(CampaignQueryService.class);
    private final StoreQueryService storeQueryService = mock(StoreQueryService.class);
    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CandidateLoadService service = new CandidateLoadService(campaignQueryService, storeQueryService,
            adCandidateRepository, meterRegistry, Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE));

    private static CampaignInfo campaign(long id, long storeId, CampaignStatus status, PauseReason reason,
                                         DiscountTargetType target, Long menuId) {
        return campaign(id, storeId, status, reason, target, menuId, CHANGED_AT);
    }

    private static CampaignInfo campaign(long id, long storeId, CampaignStatus status, PauseReason reason,
                                         DiscountTargetType target, Long menuId, LocalDateTime changedAt) {
        return new CampaignInfo(id, 1L, storeId, status, 30, LocalTime.of(12, 0), LocalTime.of(14, 0),
                TODAY.minusDays(3), TODAY.plusDays(3), target, DiscountType.PERCENT, 20, menuId, 1000,
                TargetGender.ALL, List.of(AgeGroup.AGE_20S, AgeGroup.AGE_30S), LocalDateTime.of(2026, 9, 1, 9, 0),
                LocalDate.of(2026, 10, 3), changedAt, reason, 10_000L);
    }

    private static StoreAdCandidateInfo store(long id) {
        return new StoreAdCandidateInfo(id, "가게" + id, "KOREAN", "https://cdn.example.com/" + id + ".jpg",
                new BigDecimal("37.4979000"), new BigDecimal("127.0276000"));
    }

    @SuppressWarnings("unchecked")
    private List<CandidateRow> upserted() {
        ArgumentCaptor<List<CandidateRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(adCandidateRepository).upsertAll(captor.capture(), eq(NOW), eq(NOW));
        return captor.getValue();
    }

    private double skipped(String reason) {
        return meterRegistry.counter(CandidateLoadService.SKIPPED_METRIC, "reason", reason).count();
    }

    @Test
    void ACTIVE_는_SERVABLE_로_예약이_있는_PAUSED_는_PAUSED_로_캠페인과_가게_값을_옮겨_적재한다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null),
                campaign(102, 56, CampaignStatus.PAUSED, PauseReason.NO_POINTS, DiscountTargetType.ALL, null)));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(store(55), store(56)));

        Map<Long, LocalDateTime> loaded = service.load(TODAY);

        List<CandidateRow> rows = upserted();
        assertThat(rows).extracting(CandidateRow::campaignId, CandidateRow::campaignState)
                .containsExactly(tuple(101L, CandidateCampaignState.SERVABLE),
                        tuple(102L, CandidateCampaignState.PAUSED));
        CandidateRow row = rows.get(0);
        assertThat(row).isEqualTo(new CandidateRow(TODAY, 101L, 55L, "가게55", "https://cdn.example.com/55.jpg", "KOREAN",
                new BigDecimal("37.4979000"), new BigDecimal("127.0276000"), 1000, "ALL", "AGE_20S,AGE_30S", "ALL",
                "PERCENT", 20, null, LocalTime.of(12, 0), LocalTime.of(14, 0), 30, LocalDateTime.of(2026, 9, 1, 9, 0),
                LocalDate.of(2026, 10, 3), CandidateCampaignState.SERVABLE, CHANGED_AT));
        assertThat(loaded).containsOnlyKeys(101L, 102L).containsEntry(101L, CHANGED_AT);
    }

    @Test
    void 가게가_없으면_그_캠페인은_적재하지_않고_센다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null),
                campaign(102, 99, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null)));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(store(55)));

        service.load(TODAY);

        assertThat(upserted()).extracting(CandidateRow::campaignId).containsExactly(101L);
        assertThat(skipped("store_missing")).isEqualTo(1);
    }

    // MENU 할인에 메뉴명이 없으면 CHECK 에 걸려 적재 전체가 실패한다(#582)
    @Test
    void 메뉴_할인은_메뉴명을_채우고_메뉴를_찾지_못하면_적재하지_않고_센다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7001L),
                campaign(102, 56, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7002L)));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(store(55), store(56)));
        when(storeQueryService.findMenusByIdIn(anyList())).thenReturn(
                new StoreMenuQueryResult(List.of(new StoreMenuInfo(7001L, 55L, null, "갈비살 정식", 12000L)), List.of()));

        service.load(TODAY);

        assertThat(upserted()).extracting(CandidateRow::campaignId, CandidateRow::discountMenuName)
                .containsExactly(tuple(101L, "갈비살 정식"));
        assertThat(skipped("menu_missing")).isEqualTo(1);
    }

    // 캠페인의 연령대에 중복이 있어도 한 번씩만 넣는다. 저장값에 중복이 남지 않게 한다(#783)
    @Test
    void 연령대의_중복은_한_번씩만_적재한다() {
        CampaignInfo duplicated = new CampaignInfo(101L, 1L, 55L, CampaignStatus.ACTIVE, 30, LocalTime.of(12, 0),
                LocalTime.of(14, 0), TODAY.minusDays(3), TODAY.plusDays(3), DiscountTargetType.ALL, DiscountType.PERCENT, 20,
                null, 1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S, AgeGroup.AGE_20S, AgeGroup.AGE_30S),
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDate.of(2026, 10, 3), CHANGED_AT, null, 10_000L);
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(duplicated));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(store(55)));

        service.load(TODAY);

        assertThat(upserted()).extracting(CandidateRow::targetAgeGroups).containsExactly("AGE_20S,AGE_30S");
    }

    /*
     * 캠페인의 메뉴가 다른 가게의 메뉴면 그 가게가 팔지 않는 메뉴의 할인이 카드에 나간다.
     * 가게 계약이 이 확인을 광고 서빙에 맡겼다(store-query-contract.md). 메뉴를 찾지 못한 것과 같이 뺀다(#783).
     */
    @Test
    void 메뉴가_캠페인의_가게_것이_아니면_적재하지_않고_센다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7001L),
                campaign(102, 56, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7002L)));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(store(55), store(56)));
        when(storeQueryService.findMenusByIdIn(anyList())).thenReturn(new StoreMenuQueryResult(List.of(
                new StoreMenuInfo(7001L, 99L, null, "다른 가게 메뉴", 12000L),
                new StoreMenuInfo(7002L, 56L, null, "돈코츠 라멘", 9000L)), List.of()));

        service.load(TODAY);

        assertThat(upserted()).extracting(CandidateRow::campaignId).containsExactly(102L);
        assertThat(skipped("menu_missing")).isEqualTo(1);
    }

    // 메뉴가 없는 것과 이미지 데이터 오류를 운영에서 가르도록 다른 reason 으로 세고 메뉴 ID 를 남긴다(#779)
    @Test
    void 데이터_오류_메뉴의_캠페인만_빼고_정상_메뉴와_전체_메뉴_캠페인은_적재한다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7001L),
                campaign(102, 56, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7002L),
                campaign(103, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null)));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(store(55), store(56)));
        when(storeQueryService.findMenusByIdIn(List.of(7001L, 7002L))).thenReturn(new StoreMenuQueryResult(
                List.of(new StoreMenuInfo(7001L, 55L, "menu.jpg", "갈비살 정식", 12000L)), List.of(7002L)));

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CandidateLoadService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var loaded = service.load(TODAY);

            assertThat(loaded).containsOnlyKeys(101L, 103L);
            assertThat(upserted()).extracting(CandidateRow::campaignId).containsExactly(101L, 103L);
            assertThat(skipped("menu_invalid")).isEqualTo(1);
            assertThat(skipped("menu_missing")).isZero();
            assertThat(appender.list).extracting(event -> event.getFormattedMessage())
                    .contains("event=ADSERVING_CANDIDATE_MENU_INVALID campaignId=102 menuId=7002 fallback=SKIP_CAMPAIGN");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 메뉴_조회의_CDN_설정_오류는_메뉴_제외로_숨기지_않고_적재를_실패시킨다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7001L)));
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(store(55)));
        var failure = new IllegalStateException("cdn.base-url 설정 누락");
        when(storeQueryService.findMenusByIdIn(List.of(7001L))).thenThrow(failure);

        assertThatThrownBy(() -> service.load(TODAY)).isSameAs(failure);

        verifyNoInteractions(adCandidateRepository);
        assertThat(skipped("menu_missing")).isZero();
    }

    // 가게 계약은 상한을 넘는 입력을 거부한다. 상한 단위로 나눠 읽어 모두 적재하는지 본다
    @Test
    void 가게가_계약의_상한을_넘어도_나눠_읽어_모두_적재한다() {
        int count = StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS + 1;
        List<CampaignInfo> campaigns = LongStream.rangeClosed(1, count)
                .mapToObj(i -> campaign(i, i, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null)).toList();
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(campaigns);
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            if (ids.size() > StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS) {
                throw new IllegalArgumentException("가게 ID 가 상한을 넘었다: " + ids.size());
            }
            return ids.stream().map(CandidateLoadServiceTest::store).toList();
        });

        service.load(TODAY);

        assertThat(upserted()).hasSize(count);
    }

    @Test
    void 보관_기간_7일이_지난_영업일의_후보를_지운다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of());

        service.load(TODAY);

        verify(adCandidateRepository).deleteByBusinessDateBefore(TODAY.minusDays(7));
    }

    @Test
    void 대상이_없으면_적재한_캠페인이_없다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of());

        assertThat(service.load(TODAY)).isEmpty();
        assertThat(upserted()).isEmpty();
    }

    // 적재 중에 커밋된 전이를 반영한다. 적재 때 읽은 시각보다 늦은 것만 고친다(#582)
    @Test
    void 적재_뒤_더_늦게_전이한_캠페인만_상태를_고친다() {
        LocalDateTime later = CHANGED_AT.plusMinutes(30);
        Map<Long, LocalDateTime> loaded = new HashMap<>(Map.of(101L, CHANGED_AT, 102L, CHANGED_AT, 103L, CHANGED_AT));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.PAUSED, PauseReason.OWNER, DiscountTargetType.ALL, null, later),
                campaign(102, 56, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null),
                campaign(103, 57, CampaignStatus.ENDED, null, DiscountTargetType.ALL, null, later)));

        service.reconcile(TODAY, loaded);

        verify(adCandidateRepository).changeState(TODAY, 101L, "PAUSED", later, NOW);
        verify(adCandidateRepository).changeState(TODAY, 103L, "PAUSED", later, NOW);
        verify(adCandidateRepository, never()).changeState(eq(TODAY), eq(102L), any(), any(), any());
    }

    @Test
    void 적재한_캠페인이_없으면_상태를_바꾸지_않는다() {
        service.reconcile(TODAY, Map.of());

        verify(adCandidateRepository, never()).changeState(any(), any(), any(), any(), any());
    }
}
