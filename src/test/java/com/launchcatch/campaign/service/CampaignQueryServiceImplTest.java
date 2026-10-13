package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignQueryServiceImplTest {

    private static final Long CAMPAIGN_ID = 1L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CampaignRepository campaignRepository;

    private CampaignQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignQueryServiceImpl(campaignRepository, FIXED_CLOCK);
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
        assertThat(info.pauseReason()).isNull();
        assertThat(info.dailyBudget()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("PAUSED 캠페인의 pauseReason을 그대로 옮긴다")
    void pauseReason을_옮긴다() {
        Campaign campaign = campaign();
        campaign.schedule(LocalDateTime.now(FIXED_CLOCK));
        campaign.activate(LocalDateTime.now(FIXED_CLOCK));
        campaign.pause(PauseReason.OWNER, LocalDateTime.now(FIXED_CLOCK));
        when(campaignRepository.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign));

        List<CampaignInfo> result = service.findAllById(List.of(CAMPAIGN_ID));

        assertThat(result.get(0).pauseReason()).isEqualTo(PauseReason.OWNER);
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

    @Test
    @DisplayName("findEligibleCampaigns는 서울 기준 오늘로 저장소를 부른다")
    void findEligibleCampaigns는_오늘로_조회한다() {
        Campaign campaign = campaign();
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        when(campaignRepository.findEligibleCampaigns(today)).thenReturn(List.of(campaign));

        List<CampaignInfo> result = service.findEligibleCampaigns();

        assertThat(result).extracting(CampaignInfo::campaignId).containsExactly(CAMPAIGN_ID);
        verify(campaignRepository).findEligibleCampaigns(today);
    }

    @Test
    @DisplayName("findByOwnerIdAndStatusIn은 저장소를 그대로 불러 매핑한다")
    void findByOwnerIdAndStatusIn은_저장소를_불러_매핑한다() {
        Campaign campaign = campaign();
        List<CampaignStatus> statuses = List.of(CampaignStatus.PAUSED);
        when(campaignRepository.findByOwnerIdAndStatusIn(10L, statuses)).thenReturn(List.of(campaign));

        List<CampaignInfo> result = service.findByOwnerIdAndStatusIn(10L, statuses);

        assertThat(result).extracting(CampaignInfo::campaignId).containsExactly(CAMPAIGN_ID);
        verify(campaignRepository).findByOwnerIdAndStatusIn(10L, statuses);
    }

    @Test
    @DisplayName("hasLiveCampaign은 DRAFT, ENDED를 제외한 상태로 저장소에 묻는다")
    void hasLiveCampaign은_저장소에_그대로_묻는다() {
        when(campaignRepository.existsByOwnerIdAndStatusNotIn(10L, List.of(CampaignStatus.DRAFT, CampaignStatus.ENDED)))
                .thenReturn(true);

        boolean result = service.hasLiveCampaign(10L);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("findOwnerIdsWithLiveCampaign은 입력이 비면 저장소를 부르지 않고 빈 Set을 돌려준다")
    void findOwnerIdsWithLiveCampaign은_빈_입력이면_빈_Set이다() {
        Set<Long> result = service.findOwnerIdsWithLiveCampaign(List.of());

        assertThat(result).isEmpty();
        verify(campaignRepository, never()).findDistinctOwnerIdsWithLiveCampaign(anyCollection(), anyList());
    }

    @Test
    @DisplayName("findOwnerIdsWithLiveCampaign은 hasLiveCampaign과 같은 조건으로 저장소를 부른다")
    void findOwnerIdsWithLiveCampaign은_같은_조건을_쓴다() {
        List<CampaignStatus> excluded = List.of(CampaignStatus.DRAFT, CampaignStatus.ENDED);
        when(campaignRepository.findDistinctOwnerIdsWithLiveCampaign(List.of(10L, 20L), excluded))
                .thenReturn(List.of(10L));

        Set<Long> result = service.findOwnerIdsWithLiveCampaign(List.of(10L, 20L));

        assertThat(result).containsExactly(10L);
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
