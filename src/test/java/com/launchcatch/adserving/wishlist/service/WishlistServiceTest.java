package com.launchcatch.adserving.wishlist.service;

import static com.launchcatch.adserving.engine.AdCandidateFixture.BUSINESS_DATE;
import static com.launchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.DiscountTarget;
import com.launchcatch.adserving.engine.entity.DiscountType;
import com.launchcatch.adserving.engine.exception.CandidateErrorCode;
import com.launchcatch.adserving.engine.exception.CandidateException;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.wishlist.dto.WishlistItemResponse;
import com.launchcatch.adserving.wishlist.dto.WishlistResponse;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("찜 목록 서비스")
class WishlistServiceTest {

    private static final Long MEMBER_ID = 3001L;
    // 한국 시각 2026-10-01 10:15 다
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 1, 10, 15).atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);

    private final WishlistRepository wishlistRepository = mock(WishlistRepository.class);
    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final ExclusionRepository exclusionRepository = mock(ExclusionRepository.class);
    private final WishlistService service = new WishlistService(wishlistRepository, adCandidateRepository, exclusionRepository, CLOCK);

    // 찜은 저장소의 insertIgnore 로 넣어 식별자와 생성 시각이 엔티티에 없다. 리플렉션으로 채운다
    private static Wishlist wish(long id, long campaignId, LocalDateTime createdAt) {
        Wishlist wish = Wishlist.fromFeed(MEMBER_ID, campaignId, campaignId + 1000, UUID.randomUUID());
        ReflectionTestUtils.setField(wish, "id", id);
        ReflectionTestUtils.setField(wish, "createdAt", createdAt);
        return wish;
    }

    private static AdCandidate menuCandidate(long campaignId) {
        return candidate(campaignId, 37.4979, 127.0276)
                .set("storeImageUrl", "https://cdn.example.com/stores/" + (campaignId + 1000) + "/logo.jpg")
                .set("discountTarget", DiscountTarget.MENU)
                .set("discountType", DiscountType.AMOUNT)
                .set("discountValue", 3000)
                .set("discountMenuName", "갈비살 정식")
                .build();
    }

    @Test
    void 저장소가_준_찜_순서대로_그날_후보의_표시_값을_채운다() {
        LocalDateTime later = LocalDateTime.of(2026, 10, 1, 10, 15, 34, 20_000_000);
        LocalDateTime earlier = LocalDateTime.of(2026, 9, 30, 11, 0);
        when(wishlistRepository.findByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID))
                .thenReturn(List.of(wish(2, 102, later), wish(1, 101, earlier)));
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignIdIn(BUSINESS_DATE, Set.of(101L, 102L)))
                .thenReturn(List.of(candidate(101, 37.4979, 127.0276).build(), menuCandidate(102)));

        WishlistResponse response = service.getWishlist(MEMBER_ID, null);

        assertThat(response.wishlist()).containsExactly(
                new WishlistItemResponse(102L, 1102L, "가게102", "https://cdn.example.com/stores/1102/logo.jpg",
                        new WishlistItemResponse.Discount(DiscountTarget.MENU, DiscountType.AMOUNT, 3000, "갈비살 정식"),
                        OffsetDateTime.of(later, ZoneOffset.ofHours(9))),
                new WishlistItemResponse(101L, 1101L, "가게101", null,
                        new WishlistItemResponse.Discount(DiscountTarget.ALL, DiscountType.PERCENT, 20, null),
                        OffsetDateTime.of(earlier, ZoneOffset.ofHours(9))));
    }

    @Test
    void 그날_후보에_없는_캠페인은_표시_값이_null_이고_식별자와_찜한_시각은_있다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 30, 11, 0);
        when(wishlistRepository.findByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID))
                .thenReturn(List.of(wish(1, 101, createdAt)));
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignIdIn(any(), anyCollection())).thenReturn(List.of());

        WishlistResponse response = service.getWishlist(MEMBER_ID, null);

        assertThat(response.wishlist()).containsExactly(new WishlistItemResponse(101L, 1101L, null, null, null,
                OffsetDateTime.of(createdAt, ZoneOffset.ofHours(9))));
    }

    @Test
    void campaignId_를_주면_그_캠페인의_찜만_읽는다() {
        when(wishlistRepository.findByMemberIdAndCampaignId(MEMBER_ID, 102L))
                .thenReturn(Optional.of(wish(2, 102, LocalDateTime.of(2026, 10, 1, 10, 0))));
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignIdIn(BUSINESS_DATE, Set.of(102L)))
                .thenReturn(List.of(menuCandidate(102)));

        WishlistResponse response = service.getWishlist(MEMBER_ID, 102L);

        assertThat(response.wishlist()).extracting(WishlistItemResponse::campaignId).containsExactly(102L);
        verify(wishlistRepository, never()).findByMemberIdOrderByCreatedAtDescIdDesc(any());
    }

    // 가게 상세가 "배열이 비었는가" 로 찜 여부를 판정한다(#416)
    @Test
    void campaignId_를_줬는데_그_찜이_없으면_빈_배열이다() {
        when(wishlistRepository.findByMemberIdAndCampaignId(MEMBER_ID, 999L)).thenReturn(Optional.empty());

        assertThat(service.getWishlist(MEMBER_ID, 999L).wishlist()).isEmpty();
    }

    @Test
    void 찜이_없으면_빈_배열이고_후보를_읽지_않는다() {
        when(wishlistRepository.findByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID)).thenReturn(List.of());

        assertThat(service.getWishlist(MEMBER_ID, null).wishlist()).isEmpty();
        verify(adCandidateRepository, never()).findByIdBusinessDateAndIdCampaignIdIn(any(), anyCollection());
    }

    @Test
    void 그날은_한국_시각의_날짜다() {
        // UTC 로는 9월 30일 15:30, 한국 시각으로는 10월 1일 00:30 이다
        Clock afterMidnight = Clock.fixed(
                LocalDateTime.of(2026, 10, 1, 0, 30).atZone(ClockConfig.ZONE).toInstant(), ZoneOffset.UTC);
        WishlistService atMidnight = new WishlistService(wishlistRepository, adCandidateRepository, exclusionRepository, afterMidnight);
        when(wishlistRepository.findByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID))
                .thenReturn(List.of(wish(1, 101, LocalDateTime.of(2026, 9, 30, 11, 0))));

        atMidnight.getWishlist(MEMBER_ID, null);

        verify(adCandidateRepository).findByIdBusinessDateAndIdCampaignIdIn(LocalDate.of(2026, 10, 1), Set.of(101L));
    }

    @Test
    void 처음_찜하면_출처_카드_없이_넣고_그날_당일_이력에_넣고_created_다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 10, 15);
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, 102L))
                .thenReturn(Optional.of(menuCandidate(102)));
        when(wishlistRepository.insertIgnore(any())).thenReturn(true);
        when(wishlistRepository.findByMemberIdAndCampaignId(MEMBER_ID, 102L))
                .thenReturn(Optional.of(wish(2, 102, createdAt)));

        WishResult result = service.wish(MEMBER_ID, 102L);

        ArgumentCaptor<Wishlist> inserted = ArgumentCaptor.forClass(Wishlist.class);
        InOrder order = inOrder(wishlistRepository, exclusionRepository);
        order.verify(wishlistRepository).insertIgnore(inserted.capture());
        order.verify(exclusionRepository).add(BUSINESS_DATE, MEMBER_ID, 102L, CLOCK.instant());
        assertThat(inserted.getValue().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(inserted.getValue().getStoreId()).isEqualTo(1102L);
        assertThat(inserted.getValue().getServeId()).isNull();
        assertThat(result).isEqualTo(new WishResult(true, new WishlistItemResponse(102L, 1102L, "가게102",
                "https://cdn.example.com/stores/1102/logo.jpg",
                new WishlistItemResponse.Discount(DiscountTarget.MENU, DiscountType.AMOUNT, 3000, "갈비살 정식"),
                OffsetDateTime.of(createdAt, ZoneOffset.ofHours(9)))));
    }

    // 다시 넣어도 집합이라 그대로다. 앞 요청에서 Valkey 에 넣지 못했으면 여기서 채워진다
    @Test
    void 이미_찜했으면_created_가_아니고_처음_찜한_시각을_돌려주며_당일_이력에_다시_넣는다() {
        LocalDateTime firstWishedAt = LocalDateTime.of(2026, 9, 30, 11, 0);
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, 101L))
                .thenReturn(Optional.of(candidate(101, 37.4979, 127.0276).build()));
        when(wishlistRepository.insertIgnore(any())).thenReturn(false);
        when(wishlistRepository.findByMemberIdAndCampaignId(MEMBER_ID, 101L))
                .thenReturn(Optional.of(wish(1, 101, firstWishedAt)));

        WishResult result = service.wish(MEMBER_ID, 101L);

        assertThat(result.created()).isFalse();
        assertThat(result.item().wishedAt()).isEqualTo(OffsetDateTime.of(firstWishedAt, ZoneOffset.ofHours(9)));
        verify(exclusionRepository).add(BUSINESS_DATE, MEMBER_ID, 101L, CLOCK.instant());
    }

    // 어제 찜하고 오늘 후보에서 빠진 캠페인도 찜 여부와 관계없이 거절한다(#420)
    @Test
    void 그날_후보가_아니면_CAMPAIGN_NOT_SERVING_이고_찜도_당일_이력도_쓰지_않는다() {
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.wish(MEMBER_ID, 999L))
                .isInstanceOf(CandidateException.class)
                .extracting("errorCode").isEqualTo(CandidateErrorCode.CAMPAIGN_NOT_SERVING);
        verify(wishlistRepository, never()).insertIgnore(any());
        verify(exclusionRepository, never()).add(any(), any(), any(), any());
    }

    // 당일 이력은 되돌릴 수 없어 찜이 실패하면 넣지 않는다. 넣으면 찜하지 않은 캠페인이 그날 피드에서 빠진다
    @Test
    void 찜을_넣지_못하면_당일_이력에_넣지_않는다() {
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, 101L))
                .thenReturn(Optional.of(candidate(101, 37.4979, 127.0276).build()));
        doThrow(new QueryTimeoutException("db")).when(wishlistRepository).insertIgnore(any());

        assertThatThrownBy(() -> service.wish(MEMBER_ID, 101L)).isInstanceOf(QueryTimeoutException.class);
        verify(exclusionRepository, never()).add(any(), any(), any(), any());
    }

    @Test
    void 찜할_때의_그날도_한국_시각의_날짜다() {
        Clock afterMidnight = Clock.fixed(
                LocalDateTime.of(2026, 10, 1, 0, 30).atZone(ClockConfig.ZONE).toInstant(), ZoneOffset.UTC);
        WishlistService atMidnight = new WishlistService(wishlistRepository, adCandidateRepository, exclusionRepository, afterMidnight);
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignId(LocalDate.of(2026, 10, 1), 101L))
                .thenReturn(Optional.of(candidate(101, 37.4979, 127.0276).build()));
        when(wishlistRepository.findByMemberIdAndCampaignId(MEMBER_ID, 101L))
                .thenReturn(Optional.of(wish(1, 101, LocalDateTime.of(2026, 10, 1, 0, 30))));

        atMidnight.wish(MEMBER_ID, 101L);

        verify(exclusionRepository).add(LocalDate.of(2026, 10, 1), MEMBER_ID, 101L, afterMidnight.instant());
    }

    @Test
    void 찜_행이_있으면_찜했다고_답한다() {
        when(wishlistRepository.existsByMemberIdAndCampaignId(MEMBER_ID, 101L)).thenReturn(true);

        assertThat(service.isWished(MEMBER_ID, 101L)).isTrue();
    }

    @Test
    void 찜_행이_없으면_찜하지_않았다고_답한다() {
        when(wishlistRepository.existsByMemberIdAndCampaignId(MEMBER_ID, 101L)).thenReturn(false);

        assertThat(service.isWished(MEMBER_ID, 101L)).isFalse();
    }

    @Test
    void 찜한_캠페인_ID_를_모두_돌려준다() {
        when(wishlistRepository.findCampaignIdsByMemberId(MEMBER_ID)).thenReturn(List.of(101L, 102L));

        assertThat(service.findWishedCampaignIds(MEMBER_ID)).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void 찜이_없으면_빈_리스트다() {
        when(wishlistRepository.findCampaignIdsByMemberId(MEMBER_ID)).thenReturn(List.of());

        assertThat(service.findWishedCampaignIds(MEMBER_ID)).isNotNull().isEmpty();
    }
}
