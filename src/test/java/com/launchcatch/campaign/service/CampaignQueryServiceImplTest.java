package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class CampaignQueryServiceImplTest {

    private static final Long CAMPAIGN_ID = 1L;
    /*
     * UTC 날짜와 서울 날짜가 갈리는 시각을 쓴다(UTC 10/12 15:30 = 서울 10/13 00:30). 자정
     * 직후라 UTC 기준으로 계산하면 하루 전 날짜가 나와서, findEligibleCampaigns가 시간대를
     * 잘못 다루면 이 테스트가 실제로 잡아낸다(두 날짜가 같으면 시간대 처리가 틀려도 통과한다).
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-12T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private EntityManager entityManager;

    private CampaignQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignQueryServiceImpl(campaignRepository, entityManager, FIXED_CLOCK);
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
        assertThat(info.discountType()).isEqualTo(DiscountType.AMOUNT);
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
        PageRequest pageable = PageRequest.of(0, CampaignQueryServiceImpl.MAX_SERVABLE_CAMPAIGNS + 1);
        when(campaignRepository.findServableOn(businessDate, pageable)).thenReturn(List.of(campaign));

        List<CampaignInfo> result = service.findServableOn(businessDate);

        assertThat(result).extracting(CampaignInfo::campaignId).containsExactly(CAMPAIGN_ID);
        verify(campaignRepository).findServableOn(businessDate, pageable);
    }

    @Test
    @DisplayName("findServableOn 결과가 10000행이면 거부하지 않는다")
    void findServableOn_결과가_10000행이면_거부하지_않는다() {
        LocalDate businessDate = LocalDate.of(2026, 10, 13);
        PageRequest pageable = PageRequest.of(0, CampaignQueryServiceImpl.MAX_SERVABLE_CAMPAIGNS + 1);
        List<Campaign> campaigns =
                Collections.nCopies(CampaignQueryServiceImpl.MAX_SERVABLE_CAMPAIGNS, campaign());
        when(campaignRepository.findServableOn(businessDate, pageable)).thenReturn(campaigns);

        List<CampaignInfo> result = service.findServableOn(businessDate);

        assertThat(result).hasSize(CampaignQueryServiceImpl.MAX_SERVABLE_CAMPAIGNS);
    }

    @Test
    @DisplayName("findServableOn 결과가 10000행을 넘으면 예외로 거부한다")
    void findServableOn_결과가_10000행을_넘으면_거부한다() {
        LocalDate businessDate = LocalDate.of(2026, 10, 13);
        PageRequest pageable = PageRequest.of(0, CampaignQueryServiceImpl.MAX_SERVABLE_CAMPAIGNS + 1);
        List<Campaign> tooMany =
                Collections.nCopies(CampaignQueryServiceImpl.MAX_SERVABLE_CAMPAIGNS + 1, campaign());
        when(campaignRepository.findServableOn(businessDate, pageable)).thenReturn(tooMany);

        assertThatThrownBy(() -> service.findServableOn(businessDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(tooMany.size()));
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
    @DisplayName("findByIdForUpdate는 저장소를 잠가 불러 매핑하고, 영속성 컨텍스트의 값을 최신으로 갱신한다")
    void findByIdForUpdate는_저장소를_잠가_불러_매핑한다() {
        Campaign campaign = campaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        Optional<CampaignInfo> result = service.findByIdForUpdate(CAMPAIGN_ID);

        assertThat(result).isPresent();
        assertThat(result.get().campaignId()).isEqualTo(CAMPAIGN_ID);
        verify(campaignRepository).findByIdForUpdate(CAMPAIGN_ID);
        verify(entityManager).refresh(campaign, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("findByIdForUpdate는 없는 캠페인이면 빈 값을 돌려준다")
    void findByIdForUpdate는_없으면_빈_값이다() {
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());

        Optional<CampaignInfo> result = service.findByIdForUpdate(CAMPAIGN_ID);

        assertThat(result).isEmpty();
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
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
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
