package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignQueryServiceImplTest {

    private static final Long CAMPAIGN_ID = 1L;

    @Mock
    private CampaignRepository campaignRepository;

    private CampaignQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignQueryServiceImpl(campaignRepository);
    }

    @Test
    @DisplayName("캠페인의 모든 필드를 CampaignInfo로 옮긴다. newPriorityUntil은 campaign에 컬럼이 없어 null이다")
    void 캠페인을_CampaignInfo로_옮긴다() {
        Campaign campaign = campaign();
        when(campaignRepository.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign));

        List<CampaignInfo> result = service.findAllById(List.of(CAMPAIGN_ID));

        assertThat(result).hasSize(1);
        CampaignInfo info = result.get(0);
        assertThat(info.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(info.ownerId()).isEqualTo(10L);
        assertThat(info.storeId()).isEqualTo(20L);
        assertThat(info.status()).isEqualTo(campaign.getStatus());
        assertThat(info.issueQuantity()).isEqualTo(100);
        assertThat(info.discountTargetType()).isEqualTo(DiscountTargetType.ALL);
        assertThat(info.discountType()).isEqualTo(DiscountType.PERCENT);
        assertThat(info.discountValue()).isEqualTo(10);
        assertThat(info.targetMenuId()).isNull();
        assertThat(info.targetRadius()).isEqualTo(1000);
        assertThat(info.targetGender()).isEqualTo(TargetGender.ALL);
        assertThat(info.targetAgeGroups()).containsExactly(AgeGroup.AGE_20S);
        assertThat(info.startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(info.endDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(info.newPriorityUntil()).isNull();
    }

    @Test
    @DisplayName("findByStoreIdIn은 저장소를 그대로 불러 매핑한다")
    void findByStoreIdIn은_저장소를_불러_매핑한다() {
        Campaign campaign = campaign();
        when(campaignRepository.findByStoreIdIn(List.of(20L))).thenReturn(List.of(campaign));

        List<CampaignInfo> result = service.findByStoreIdIn(List.of(20L));

        assertThat(result).extracting(CampaignInfo::storeId).containsExactly(20L);
        verify(campaignRepository).findByStoreIdIn(List.of(20L));
    }

    @Test
    @DisplayName("findServableOn은 저장소를 그대로 불러 매핑한다")
    void findServableOn은_저장소를_불러_매핑한다() {
        Campaign campaign = campaign();
        LocalDate businessDate = LocalDate.of(2026, 10, 13);
        when(campaignRepository.findServableOn(businessDate)).thenReturn(List.of(campaign));

        List<CampaignInfo> result = service.findServableOn(businessDate);

        assertThat(result).extracting(CampaignInfo::campaignId).containsExactly(CAMPAIGN_ID);
        verify(campaignRepository).findServableOn(businessDate);
    }

    private Campaign campaign() {
        Campaign campaign = Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.of(2026, 10, 1, 0, 0));
        setId(campaign, CAMPAIGN_ID);
        return campaign;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = BaseTimeEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
