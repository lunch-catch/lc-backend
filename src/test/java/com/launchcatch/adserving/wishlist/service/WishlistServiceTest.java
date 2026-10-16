package com.launchcatch.adserving.wishlist.service;

import static com.launchcatch.adserving.engine.AdCandidateFixture.BUSINESS_DATE;
import static com.launchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.DiscountTarget;
import com.launchcatch.adserving.engine.entity.DiscountType;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("찜 목록 서비스")
class WishlistServiceTest {

    private static final Long MEMBER_ID = 3001L;
    // 한국 시각 2026-10-01 10:15 다
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 1, 10, 15).atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);

    private final WishlistRepository wishlistRepository = mock(WishlistRepository.class);
    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final WishlistService service = new WishlistService(wishlistRepository, adCandidateRepository, CLOCK);

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
        WishlistService atMidnight = new WishlistService(wishlistRepository, adCandidateRepository, afterMidnight);
        when(wishlistRepository.findByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID))
                .thenReturn(List.of(wish(1, 101, LocalDateTime.of(2026, 9, 30, 11, 0))));

        atMidnight.getWishlist(MEMBER_ID, null);

        verify(adCandidateRepository).findByIdBusinessDateAndIdCampaignIdIn(LocalDate.of(2026, 10, 1), Set.of(101L));
    }
}
