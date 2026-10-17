package com.launchcatch.adserving.feed.service;

import static com.launchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.exception.CandidateErrorCode;
import com.launchcatch.adserving.engine.exception.CandidateException;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.feed.dto.SwipeRequest;
import com.launchcatch.adserving.feed.dto.SwipeResponse;
import com.launchcatch.adserving.feed.entity.SwipeAction;
import com.launchcatch.adserving.feed.entity.SwipeLog;
import com.launchcatch.adserving.feed.entity.SwipeLogId;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("스와이프 서비스")
class SwipeServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final UUID SERVE_ID = UUID.fromString("01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 35).atZone(ClockConfig.ZONE).toInstant();

    private final SwipeLogRepository swipeLogRepository = mock(SwipeLogRepository.class);
    private final WishlistRepository wishlistRepository = mock(WishlistRepository.class);
    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final ExclusionRepository exclusionRepository = mock(ExclusionRepository.class);
    private final SwipeService service = new SwipeService(swipeLogRepository, wishlistRepository,
            adCandidateRepository, exclusionRepository, Clock.fixed(NOW, ClockConfig.ZONE));

    private static SwipeRequest request(SwipeAction action) {
        return new SwipeRequest(SERVE_ID.toString(), 101L, action.name(), OffsetDateTime.parse("2026-10-01T10:15:34.020+09:00"));
    }

    @BeforeEach
    void setUp() {
        // 가게는 1101, 업종은 KOREAN 이다(AdCandidateFixture)
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignId(TODAY, 101L))
                .thenReturn(Optional.of(candidate(101, 37.5, 127.0).build()));
        when(swipeLogRepository.existsById(any())).thenReturn(false);
        when(swipeLogRepository.insertIgnore(any())).thenReturn(true);
    }

    private SwipeLog savedSwipe() {
        ArgumentCaptor<SwipeLog> captor = ArgumentCaptor.forClass(SwipeLog.class);
        verify(swipeLogRepository).insertIgnore(captor.capture());
        return captor.getValue();
    }

    @Test
    void 찜하면_찜을_먼저_넣고_스와이프를_남기고_그날_당일_이력에_넣는다() {
        SwipeResponse response = service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        assertThat(response.applied()).isTrue();
        InOrder order = inOrder(wishlistRepository, swipeLogRepository, exclusionRepository);
        ArgumentCaptor<Wishlist> wish = ArgumentCaptor.forClass(Wishlist.class);
        order.verify(wishlistRepository).insertIgnore(wish.capture());
        order.verify(swipeLogRepository).insertIgnore(any());
        order.verify(exclusionRepository).add(TODAY, MEMBER_ID, 101L, NOW);
        assertThat(wish.getValue().getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(wish.getValue().getCampaignId()).isEqualTo(101L);
        assertThat(wish.getValue().getStoreId()).isEqualTo(1101L);
        assertThat(wish.getValue().getServeId()).isEqualTo(SERVE_ID);
    }

    @Test
    void 스와이프에는_그날_후보의_업종과_한국_시각의_넘긴_시각을_남긴다() {
        service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        SwipeLog log = savedSwipe();
        assertThat(log.getId()).isEqualTo(SwipeLogId.of(SERVE_ID, MEMBER_ID));
        assertThat(log.getBusinessDate()).isEqualTo(TODAY);
        assertThat(log.getCampaignId()).isEqualTo(101L);
        assertThat(log.getCategoryCode()).isEqualTo("KOREAN");
        assertThat(log.getAction()).isEqualTo(SwipeAction.WISH);
        assertThat(log.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 10, 15, 34, 20_000_000));
    }

    @Test
    void 패스하면_찜에_넣지_않고_스와이프와_당일_이력만_남긴다() {
        SwipeResponse response = service.swipe(MEMBER_ID, request(SwipeAction.PASS));

        assertThat(response.applied()).isTrue();
        verify(wishlistRepository, never()).insertIgnore(any());
        assertThat(savedSwipe().getAction()).isEqualTo(SwipeAction.PASS);
        verify(exclusionRepository).add(TODAY, MEMBER_ID, 101L, NOW);
    }

    @Test
    void 같은_카드를_이미_넘겼으면_찜도_기록도_하지_않고_applied_false_다() {
        when(swipeLogRepository.existsById(SwipeLogId.of(SERVE_ID, MEMBER_ID))).thenReturn(true);

        SwipeResponse response = service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        assertThat(response.applied()).isFalse();
        verifyNoInteractions(wishlistRepository, exclusionRepository);
        verify(swipeLogRepository, never()).insertIgnore(any());
    }

    @Test
    void 동시에_와서_먼저_넣은_쪽이_있으면_applied_false_이고_당일_이력은_다시_넣지_않는다() {
        when(swipeLogRepository.insertIgnore(any())).thenReturn(false);

        SwipeResponse response = service.swipe(MEMBER_ID, request(SwipeAction.PASS));

        assertThat(response.applied()).isFalse();
        verifyNoInteractions(exclusionRepository);
    }

    @Test
    void 오늘_후보에_없는_캠페인이면_ADSERVING_004_이고_아무것도_남기지_않는다() {
        when(adCandidateRepository.findByIdBusinessDateAndIdCampaignId(TODAY, 101L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.swipe(MEMBER_ID, request(SwipeAction.WISH)))
                .isInstanceOf(CandidateException.class)
                .extracting("errorCode").isEqualTo(CandidateErrorCode.CAMPAIGN_NOT_SERVING);
        verifyNoInteractions(wishlistRepository, exclusionRepository);
        verify(swipeLogRepository, never()).insertIgnore(any());
    }
}
