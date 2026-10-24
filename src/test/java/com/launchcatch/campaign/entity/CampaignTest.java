package com.launchcatch.campaign.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CampaignTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    @Test
    @DisplayName("예산과 집행 기간을 아직 안 정한 단계별 저장 중인 DRAFT도 만들어진다")
    void 예산과_기간이_없어도_DRAFT가_만들어진다() {
        Campaign campaign = campaign(null, null, null);

        assertThat(campaign.getDailyBudget()).isNull();
        assertThat(campaign.getStartDate()).isNull();
        assertThat(campaign.getEndDate()).isNull();
    }

    @Test
    @DisplayName("dailyBudget이 있으면 양수여야 한다")
    void dailyBudget이_있으면_양수여야_한다() {
        assertThatThrownBy(() -> campaign(0L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("startDate와 endDate가 둘 다 있으면 순서를 검사한다")
    void 기간이_둘다_있으면_순서를_검사한다() {
        assertThatThrownBy(() -> campaign(10_000L, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("집행 기간을 안 정한 캠페인은 activate를 거부한다")
    void 기간이_없으면_activate를_거부한다() {
        Campaign campaign = campaign(null, null, null);
        campaign.schedule(NOW);

        assertThatThrownBy(() -> campaign.activate(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("updateCouponCondition은 1단계 필드만 바꾸고 노출 대상과 예산·기간은 그대로 둔다")
    void updateCouponCondition은_1단계_필드만_바꾼다() {
        Campaign campaign = Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                NOW);

        campaign.updateCouponCondition(
                DiscountTargetType.MENU, DiscountType.AMOUNT, 3000, 99L,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                8000, "포장만 가능");

        assertThat(campaign.getDiscountTargetType()).isEqualTo(DiscountTargetType.MENU);
        assertThat(campaign.getDiscountType()).isEqualTo(DiscountType.AMOUNT);
        assertThat(campaign.getDiscountValue()).isEqualTo(3000);
        assertThat(campaign.getTargetMenuId()).isEqualTo(99L);
        assertThat(campaign.getIssueQuantity()).isEqualTo(50);
        assertThat(campaign.getUsableStartTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(campaign.getUsableEndTime()).isEqualTo(LocalTime.of(13, 30));
        assertThat(campaign.getMinOrderAmount()).isEqualTo(8000);
        assertThat(campaign.getNotice()).isEqualTo("포장만 가능");
        assertThat(campaign.getTargetRadius()).isEqualTo(1000);
        assertThat(campaign.getTargetGender()).isEqualTo(TargetGender.ALL);
        assertThat(campaign.getTargetAgeGroups()).containsExactly(AgeGroup.AGE_20S);
        assertThat(campaign.getDailyBudget()).isEqualTo(10_000L);
        assertThat(campaign.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(campaign.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    @DisplayName("DRAFT가 아니면 updateCouponCondition을 거부한다")
    void DRAFT가_아니면_updateCouponCondition을_거부한다() {
        Campaign campaign = campaign(10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        campaign.schedule(NOW);

        assertThatThrownBy(() -> campaign.updateCouponCondition(
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("updateCouponCondition도 할인율 불변식을 다시 검증한다")
    void updateCouponCondition도_할인율_불변식을_다시_검증한다() {
        Campaign campaign = campaign(10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThatThrownBy(() -> campaign.updateCouponCondition(
                DiscountTargetType.MENU, DiscountType.PERCENT, 101, 42L,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /*
     * 팀 논의로 바뀐 사양이다(#431). discountTargetType이 ALL이면 discountType은 AMOUNT만
     * 허용한다. create와 updateCouponCondition 양쪽 경로가 모두 requireDiscountValue를 거친다.
     */
    @Test
    @DisplayName("전체 메뉴(ALL)에 PERCENT는 생성 시점부터 거부한다")
    void 생성시_전체_메뉴에_PERCENT는_거부한다() {
        assertThatThrownBy(() -> Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateCouponCondition도 전체 메뉴(ALL)에 PERCENT를 거부한다")
    void updateCouponCondition도_전체_메뉴에_PERCENT를_거부한다() {
        Campaign campaign = campaign(10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThatThrownBy(() -> campaign.updateCouponCondition(
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SCHEDULED만 취소해 DRAFT로 되돌리고 상태 변경 시각을 남긴다")
    void SCHEDULED만_취소한다() {
        Campaign campaign = campaign(10_000L, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> campaign.cancel(NOW)).isInstanceOf(IllegalStateException.class);

        campaign.schedule(NOW);
        campaign.cancel(NOW.plusHours(1));

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaign.getStatusChangedAt()).isEqualTo(NOW.plusHours(1));

        campaign.schedule(NOW.plusHours(2));
        campaign.activate(NOW.plusHours(3));
        assertThatThrownBy(() -> campaign.cancel(NOW.plusHours(4))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SCHEDULED만 시작 전에 끝낼 수 있고 ENDED로 바뀌며 상태 변경 시각을 남긴다(#902)")
    void SCHEDULED만_시작_전에_끝낸다() {
        Campaign campaign = campaign(10_000L, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> campaign.endBeforeStart(NOW)).isInstanceOf(IllegalStateException.class);

        campaign.schedule(NOW);
        campaign.endBeforeStart(NOW.plusHours(1));

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
        assertThat(campaign.getStatusChangedAt()).isEqualTo(NOW.plusHours(1));
        assertThatThrownBy(() -> campaign.endBeforeStart(NOW.plusHours(2))).isInstanceOf(IllegalStateException.class);
    }

    private Campaign campaign(Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        return Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                dailyBudget, startDate, endDate,
                NOW);
    }
}
