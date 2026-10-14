package com.launchcatch.campaign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CampaignRepositoryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 예산과_기간이_없는_단계별_저장_중인_DRAFT도_다시_읽힌다() {
        Campaign campaign = Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.of(2026, 10, 13, 9, 0));
        Long id = campaignRepository.save(campaign).getId();
        entityManager.flush();
        entityManager.clear();

        Campaign reloaded = campaignRepository.findById(id).orElseThrow();

        assertThat(reloaded.getDailyBudget()).isNull();
        assertThat(reloaded.getStartDate()).isNull();
        assertThat(reloaded.getEndDate()).isNull();
    }

    @Test
    void PAUSED_캠페인이_종료되면_paused_reason이_지워져_CHECK_제약을_어기지_않는다() {
        Campaign campaign = Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.of(2026, 10, 13, 9, 0));
        campaign.schedule(LocalDateTime.of(2026, 10, 13, 9, 0));
        campaign.activate(LocalDateTime.of(2026, 10, 13, 9, 0));
        campaign.pause(PauseReason.ADMIN, LocalDateTime.of(2026, 10, 13, 9, 0));
        Long id = campaignRepository.save(campaign).getId();
        entityManager.flush();
        entityManager.clear();

        Campaign reloaded = campaignRepository.findById(id).orElseThrow();
        reloaded.end(LocalDateTime.of(2026, 10, 13, 10, 0));
        entityManager.flush();

        assertThat(reloaded.getPausedReason()).isNull();
    }

    @Test
    void findEligibleCampaigns는_오늘_기간에_든_SCHEDULED_ACTIVE만_돌려준다() {
        LocalDate today = LocalDate.of(2026, 10, 13);

        Campaign scheduled = save(draftWithPeriod(1L, today, today.plusDays(10)));
        scheduled.schedule(now());

        Campaign active = save(draftWithPeriod(2L, today.minusDays(5), today.plusDays(5)));
        active.schedule(now());
        active.activate(now());

        Campaign draft = save(draftWithPeriod(3L, today, today.plusDays(10)));

        Campaign ended = save(draftWithPeriod(4L, today, today.plusDays(10)));
        ended.schedule(now());
        ended.activate(now());
        ended.end(now());

        Campaign outOfPeriod = save(draftWithPeriod(5L, today.plusDays(1), today.plusDays(10)));
        outOfPeriod.schedule(now());

        entityManager.flush();

        List<Campaign> result = campaignRepository.findEligibleCampaigns(today);

        assertThat(result).extracting(Campaign::getId)
                .containsExactlyInAnyOrder(scheduled.getId(), active.getId());
    }

    @Test
    void existsByOwnerIdAndStatusNotIn_집행중인_상태만_true다() {
        Long ownerId = 100L;
        Campaign draft = save(draftWithOwner(ownerId, 1L));

        entityManager.flush();

        assertThat(campaignRepository.existsByOwnerIdAndStatusNotIn(
                ownerId, List.of(CampaignStatus.DRAFT, CampaignStatus.ENDED))).isFalse();

        Campaign active = save(draftWithOwner(ownerId, 2L));
        active.schedule(now());
        active.activate(now());
        entityManager.flush();

        assertThat(campaignRepository.existsByOwnerIdAndStatusNotIn(
                ownerId, List.of(CampaignStatus.DRAFT, CampaignStatus.ENDED))).isTrue();
    }

    @Test
    void findDistinctOwnerIdsWithLiveCampaign은_집행중인_점주만_돌려준다() {
        Long liveOwnerId = 200L;
        Campaign active = save(draftWithOwner(liveOwnerId, 1L));
        active.schedule(now());
        active.activate(now());

        Long draftOnlyOwnerId = 201L;
        save(draftWithOwner(draftOnlyOwnerId, 2L));

        entityManager.flush();

        List<Long> result = campaignRepository.findDistinctOwnerIdsWithLiveCampaign(
                List.of(liveOwnerId, draftOnlyOwnerId), List.of(CampaignStatus.DRAFT, CampaignStatus.ENDED));

        assertThat(result).containsExactly(liveOwnerId);
    }

    @Test
    void findByStoreIdAndStatus는_그_가게의_그_상태만_돌려준다() {
        Long storeId = 400L;
        Campaign draft = save(draftWithOwner(10L, storeId));

        entityManager.flush();

        Optional<Campaign> found = campaignRepository.findByStoreIdAndStatus(storeId, CampaignStatus.DRAFT);

        assertThat(found).map(Campaign::getId).contains(draft.getId());
        assertThat(campaignRepository.findByStoreIdAndStatus(storeId, CampaignStatus.ACTIVE)).isEmpty();
    }

    @Test
    void 가게당_DRAFT_캠페인은_하나만_저장할_수_있다() {
        Long storeId = 401L;
        save(draftWithOwner(10L, storeId));
        entityManager.flush();

        Campaign secondDraft = draftWithOwner(11L, storeId);

        assertThatThrownBy(() -> {
            campaignRepository.save(secondDraft);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByOwnerIdAndStatusIn은_그_점주의_그_상태만_돌려준다() {
        Long ownerId = 300L;
        Campaign paused = save(draftWithOwner(ownerId, 1L));
        paused.schedule(now());
        paused.activate(now());
        paused.pause(PauseReason.OWNER, now());

        Campaign active = save(draftWithOwner(ownerId, 2L));
        active.schedule(now());
        active.activate(now());

        entityManager.flush();

        List<Campaign> result =
                campaignRepository.findByOwnerIdAndStatusIn(ownerId, List.of(CampaignStatus.PAUSED));

        assertThat(result).extracting(Campaign::getId).containsExactly(paused.getId());
    }

    @Test
    void findByStoreIdIn의_조회가_store_id_인덱스를_탄다() {
        String plan = explainJson("SELECT * FROM campaign WHERE store_id IN (1)");

        assertThat(plan).contains("idx_campaign_store_id");
    }

    @Test
    void findServableOn의_조회가_status_기간_인덱스를_탄다() {
        String plan = explainJson(
                "SELECT * FROM campaign WHERE start_date <= '2026-10-13' AND end_date >= '2026-10-13' "
                        + "AND (status = 'ACTIVE' "
                        + "OR (status = 'PAUSED' AND paused_reason IN ('OWNER', 'NO_POINTS')))");

        assertThat(plan).contains("idx_campaign_status_period");
    }

    @Test
    void findEligibleCampaigns의_조회가_status_기간_인덱스를_탄다() {
        String plan = explainJson(
                "SELECT * FROM campaign WHERE status IN ('SCHEDULED', 'ACTIVE') "
                        + "AND start_date <= '2026-10-13' AND end_date >= '2026-10-13'");

        assertThat(plan).contains("idx_campaign_status_period");
    }

    @Test
    void hasLiveCampaign과_findByOwnerIdAndStatusIn의_조회가_owner_id_인덱스를_탄다() {
        String existsPlan = explainJson("SELECT * FROM campaign WHERE owner_id = 1 AND status NOT IN ('DRAFT', 'ENDED')");
        String statusInPlan = explainJson("SELECT * FROM campaign WHERE owner_id = 1 AND status IN ('PAUSED')");
        String ownerIdsPlan = explainJson(
                "SELECT DISTINCT owner_id FROM campaign WHERE owner_id IN (1, 2) AND status NOT IN ('DRAFT', 'ENDED')");

        assertThat(existsPlan).contains("idx_campaign_owner_id");
        assertThat(statusInPlan).contains("idx_campaign_owner_id");
        assertThat(ownerIdsPlan).contains("idx_campaign_owner_id");
    }

    private String explainJson(String sql) {
        return (String) entityManager.createNativeQuery("EXPLAIN FORMAT=JSON " + sql).getSingleResult();
    }

    private Campaign save(Campaign campaign) {
        return campaignRepository.save(campaign);
    }

    private Campaign draftWithPeriod(Long storeId, LocalDate startDate, LocalDate endDate) {
        return Campaign.create(
                10L, storeId,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, startDate, endDate,
                now());
    }

    private Campaign draftWithOwner(Long ownerId, Long storeId) {
        return Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                now());
    }

    private LocalDateTime now() {
        return LocalDateTime.of(2026, 10, 13, 9, 0);
    }
}
