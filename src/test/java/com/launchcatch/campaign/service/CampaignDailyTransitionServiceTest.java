package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.entity.ActorType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignDailyTransitionServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 1);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignStatusService campaignStatusService;

    private CampaignDailyTransitionService service;

    @BeforeEach
    void setUp() {
        service = new CampaignDailyTransitionService(campaignRepository, campaignStatusService);
    }

    private Campaign campaign(Long id, Long storeId) {
        Campaign campaign = mock(Campaign.class);
        lenient().when(campaign.getId()).thenReturn(id);
        lenient().when(campaign.getStoreId()).thenReturn(storeId);
        return campaign;
    }

    @Test
    @DisplayName("집행 기간이 끝난 캠페인을 SYSTEM 행위자로 하나씩 종료한다")
    void 집행_기간이_끝난_캠페인을_종료한다() {
        when(campaignRepository.findIdsToEnd(BUSINESS_DATE)).thenReturn(List.of(1L, 2L));

        int ended = service.endExpired(BUSINESS_DATE);

        assertThat(ended).isEqualTo(2);
        verify(campaignStatusService).end(1L, ActorType.SYSTEM, BUSINESS_DATE);
        verify(campaignStatusService).end(2L, ActorType.SYSTEM, BUSINESS_DATE);
    }

    @Test
    @DisplayName("종료 대상이 없으면 아무것도 하지 않는다(같은 영업일에 다시 돌아도 결과가 같다)")
    void 종료_대상이_없으면_아무것도_하지_않는다() {
        when(campaignRepository.findIdsToEnd(BUSINESS_DATE)).thenReturn(List.of());

        assertThat(service.endExpired(BUSINESS_DATE)).isZero();

        verify(campaignStatusService, never()).end(anyLong(), org.mockito.ArgumentMatchers.any(ActorType.class), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("한 캠페인의 종료가 실패해도 나머지는 처리하고 마지막에 예외로 단계를 실패시킨다")
    void 종료가_하나_실패해도_나머지는_처리하고_단계를_실패시킨다() {
        when(campaignRepository.findIdsToEnd(BUSINESS_DATE)).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalArgumentException("메뉴가 가게 소속이 아니다"))
                .when(campaignStatusService).end(1L, ActorType.SYSTEM, BUSINESS_DATE);

        assertThatThrownBy(() -> service.endExpired(BUSINESS_DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("END_EXPIRED").hasMessageContaining("failedCount=1");

        verify(campaignStatusService).end(2L, ActorType.SYSTEM, BUSINESS_DATE);
    }

    @Test
    @DisplayName("집행 시작일이 된 캠페인은 같은 가게의 기존 ACTIVE 를 먼저 종료한 뒤 켠다")
    void 시작일이_된_캠페인은_기존_ACTIVE_를_종료한_뒤_켠다() {
        Campaign next = campaign(10L, 20L);
        when(campaignRepository.findToStart(BUSINESS_DATE)).thenReturn(List.of(next));
        when(campaignRepository.findOtherActiveIdsByStoreId(20L, 10L)).thenReturn(List.of(5L));

        int started = service.startScheduled(BUSINESS_DATE);

        assertThat(started).isEqualTo(1);
        InOrder inOrder = inOrder(campaignStatusService);
        inOrder.verify(campaignStatusService).end(5L, ActorType.SYSTEM, BUSINESS_DATE);
        inOrder.verify(campaignStatusService).activate(10L, BUSINESS_DATE);
    }

    @Test
    @DisplayName("같은 가게에 기존 ACTIVE 가 없으면 종료 없이 켜기만 한다")
    void 기존_ACTIVE_가_없으면_켜기만_한다() {
        Campaign next = campaign(10L, 20L);
        when(campaignRepository.findToStart(BUSINESS_DATE)).thenReturn(List.of(next));
        when(campaignRepository.findOtherActiveIdsByStoreId(20L, 10L)).thenReturn(List.of());

        service.startScheduled(BUSINESS_DATE);

        verify(campaignStatusService, never()).end(anyLong(), eq(ActorType.SYSTEM), eq(BUSINESS_DATE));
        verify(campaignStatusService).activate(10L, BUSINESS_DATE);
    }

    @Test
    @DisplayName("한 캠페인을 켜는 데 실패해도 나머지는 켜고 마지막에 예외로 단계를 실패시킨다")
    void 켜기가_하나_실패해도_나머지는_켜고_단계를_실패시킨다() {
        Campaign first = campaign(10L, 20L);
        Campaign second = campaign(11L, 21L);
        when(campaignRepository.findToStart(BUSINESS_DATE)).thenReturn(List.of(first, second));
        when(campaignRepository.findOtherActiveIdsByStoreId(anyLong(), anyLong())).thenReturn(List.of());
        doThrow(new IllegalStateException("only SCHEDULED campaign can be activated"))
                .when(campaignStatusService).activate(10L, BUSINESS_DATE);

        assertThatThrownBy(() -> service.startScheduled(BUSINESS_DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("START_SCHEDULED").hasMessageContaining("failedCount=1");

        verify(campaignStatusService).activate(11L, BUSINESS_DATE);
    }
}
