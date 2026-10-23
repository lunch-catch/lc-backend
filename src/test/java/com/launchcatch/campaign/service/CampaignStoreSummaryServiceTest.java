package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignStoreSummaryServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 1);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;
    @Captor
    private ArgumentCaptor<List<StoreCampaignSummaryUpdate>> updatesCaptor;

    private CampaignStoreSummaryService service;

    @BeforeEach
    void setUp() {
        service = new CampaignStoreSummaryService(campaignRepository, storeCampaignSummaryUpdater);
    }

    private Campaign activeCampaign(Long id, Long storeId) {
        Campaign campaign = mock(Campaign.class);
        lenient().when(campaign.getId()).thenReturn(id);
        lenient().when(campaign.getStoreId()).thenReturn(storeId);
        lenient().when(campaign.getStatus()).thenReturn(CampaignStatus.ACTIVE);
        lenient().when(campaign.getDiscountTargetType()).thenReturn(DiscountTargetType.ALL);
        lenient().when(campaign.getDiscountType()).thenReturn(DiscountType.AMOUNT);
        lenient().when(campaign.getDiscountValue()).thenReturn(3000);
        lenient().when(campaign.getUsableStartTime()).thenReturn(LocalTime.of(11, 0));
        lenient().when(campaign.getUsableEndTime()).thenReturn(LocalTime.of(14, 0));
        return campaign;
    }

    @Test
    @DisplayName("ACTIVE 캠페인의 요약을 새 영업일 몫으로 한 번에 저장한다")
    void ACTIVE_캠페인의_요약을_한_번에_저장한다() {
        when(campaignRepository.findByStatusOrderById(CampaignStatus.ACTIVE))
                .thenReturn(List.of(activeCampaign(1L, 10L), activeCampaign(2L, 20L)));

        int prepared = service.prepare(BUSINESS_DATE);

        assertThat(prepared).isEqualTo(2);
        verify(storeCampaignSummaryUpdater).updateAll(updatesCaptor.capture());
        List<StoreCampaignSummaryUpdate> updates = updatesCaptor.getValue();
        assertThat(updates).extracting(StoreCampaignSummaryUpdate::campaignId).containsExactly(1L, 2L);
        assertThat(updates).extracting(StoreCampaignSummaryUpdate::storeId).containsExactly(10L, 20L);
        assertThat(updates.get(0).command().businessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(updates.get(0).command().campaignStatus()).isEqualTo(StoreCampaignStatus.ACTIVE);
        assertThat(updates.get(0).command().discountTargetType()).isEqualTo(StoreDiscountTargetType.ALL);
        assertThat(updates.get(0).command().discountType()).isEqualTo(StoreDiscountType.AMOUNT);
        assertThat(updates.get(0).command().discountValue()).isEqualTo(3000);
    }

    @Test
    @DisplayName("ACTIVE 캠페인이 없으면 요약 저장을 부르지 않는다")
    void 대상이_없으면_저장을_부르지_않는다() {
        when(campaignRepository.findByStatusOrderById(CampaignStatus.ACTIVE)).thenReturn(List.of());

        assertThat(service.prepare(BUSINESS_DATE)).isZero();

        verify(storeCampaignSummaryUpdater, never()).updateAll(anyList());
    }

    @Test
    @DisplayName("요약 저장이 실패하면 예외를 그대로 올려 단계를 실패시킨다")
    void 저장이_실패하면_예외를_올린다() {
        when(campaignRepository.findByStatusOrderById(CampaignStatus.ACTIVE))
                .thenReturn(List.of(activeCampaign(1L, 10L)));
        doThrow(new IllegalArgumentException("할인 대상 메뉴가 해당 가게에 속하지 않습니다."))
                .when(storeCampaignSummaryUpdater).updateAll(anyList());

        assertThatThrownBy(() -> service.prepare(BUSINESS_DATE)).isInstanceOf(IllegalArgumentException.class);
    }
}
