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

import com.launchcatch.adserving.contract.SwipeAction;
import com.launchcatch.adserving.contract.SwipedEvent;
import com.launchcatch.adserving.engine.exception.CandidateErrorCode;
import com.launchcatch.adserving.engine.exception.CandidateException;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.feed.dto.SwipeRequest;
import com.launchcatch.adserving.feed.dto.SwipeResponse;
import com.launchcatch.adserving.feed.entity.SwipeLog;
import com.launchcatch.adserving.feed.entity.SwipeLogId;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.adserving.wishlist.service.WishlistService;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

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
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final WishlistService wishlistService = mock(WishlistService.class);
    private final SwipeService service = new SwipeService(swipeLogRepository, wishlistRepository, wishlistService,
            adCandidateRepository, exclusionRepository, eventPublisher, Clock.fixed(NOW, ClockConfig.ZONE));

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
        Wishlist wish = Wishlist.fromFeed(MEMBER_ID, 101L, 1101L, SERVE_ID);
        ReflectionTestUtils.setField(wish, "id", 7001L);
        when(wishlistRepository.findByMemberIdAndCampaignId(MEMBER_ID, 101L)).thenReturn(Optional.of(wish));
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

    @Test
    void 찜하면_그_찜의_id_와_한국_시각의_넘긴_시각으로_찜_이벤트를_발행한다() {
        service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        verify(eventPublisher).publishEvent(new SwipedEvent("swipe:" + SERVE_ID + ":" + MEMBER_ID,
                LocalDateTime.of(2026, 10, 1, 10, 15, 34, 20_000_000), MEMBER_ID, 101L, 1101L, SERVE_ID,
                SwipeAction.WISH, 7001L));
    }

    @Test
    void 패스하면_찜_id_없이_발행한다() {
        service.swipe(MEMBER_ID, request(SwipeAction.PASS));

        verify(eventPublisher).publishEvent(new SwipedEvent("swipe:" + SERVE_ID + ":" + MEMBER_ID,
                LocalDateTime.of(2026, 10, 1, 10, 15, 34, 20_000_000), MEMBER_ID, 101L, 1101L, SERVE_ID,
                SwipeAction.PASS, null));
    }

    @Test
    void 이미_넘긴_카드면_발행하지_않는다() {
        when(swipeLogRepository.existsById(SwipeLogId.of(SERVE_ID, MEMBER_ID))).thenReturn(true);

        service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 동시에_와서_먼저_넣은_쪽이_있으면_발행하지_않는다() {
        when(swipeLogRepository.insertIgnore(any())).thenReturn(false);

        service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        verifyNoInteractions(eventPublisher);
    }

    // 그날 끝난 캠페인도 찜에 들어간다. 찜하면 끝났는지 보고 종료로 표시하게 한다(#780)
    @Test
    void 찜하면_찜을_넣은_뒤_캠페인이_끝났는지_보게_한다() {
        service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        InOrder order = inOrder(wishlistRepository, wishlistService);
        order.verify(wishlistRepository).insertIgnore(any());
        order.verify(wishlistService).endIfCampaignEnded(101L);
    }

    @Test
    void 패스면_캠페인이_끝났는지_보지_않는다() {
        service.swipe(MEMBER_ID, request(SwipeAction.PASS));

        verify(wishlistService, never()).endIfCampaignEnded(any());
    }

    // 넣은 뒤 같은 사용자의 찜 삭제가 먼저 커밋되면 다시 읽을 찜이 없다. 찜 id 없이 이어 간다(#783)
    @Test
    void 넣은_찜이_곧바로_지워져도_찜_id_없이_스와이프를_남긴다() {
        when(wishlistRepository.findByMemberIdAndCampaignId(MEMBER_ID, 101L)).thenReturn(Optional.empty());

        SwipeResponse response = service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        assertThat(response.applied()).isTrue();
        verify(eventPublisher).publishEvent(new SwipedEvent("swipe:" + SERVE_ID + ":" + MEMBER_ID,
                LocalDateTime.of(2026, 10, 1, 10, 15, 34, 20_000_000), MEMBER_ID, 101L, 1101L, SERVE_ID,
                SwipeAction.WISH, null));
    }

    /*
     * 같은 카드의 PASS 가 기록을 먼저 넣으면 이 WISH 는 진다. 패스한 카드가 찜에 남지 않게 이 요청이 새로 넣은 찜을 지운다.
     * 이미 있던 찜은 다른 카드나 가게 상세에서 넣은 것이라 둔다(#783).
     */
    @Test
    void 기록_넣기에서_진_찜은_이_요청이_새로_넣은_찜을_지운다() {
        when(wishlistRepository.insertIgnore(any())).thenReturn(true);
        when(swipeLogRepository.insertIgnore(any())).thenReturn(false);

        SwipeResponse response = service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        assertThat(response.applied()).isFalse();
        verify(wishlistRepository).deleteByMemberIdAndCampaignId(MEMBER_ID, 101L);
    }

    @Test
    void 기록_넣기에서_져도_이미_있던_찜은_지우지_않는다() {
        when(wishlistRepository.insertIgnore(any())).thenReturn(false);
        when(swipeLogRepository.insertIgnore(any())).thenReturn(false);

        service.swipe(MEMBER_ID, request(SwipeAction.WISH));

        verify(wishlistRepository, never()).deleteByMemberIdAndCampaignId(any(), any());
    }
}
