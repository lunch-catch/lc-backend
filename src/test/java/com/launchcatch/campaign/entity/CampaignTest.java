package com.launchcatch.campaign.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.campaign.contract.AgeGroup;
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

    private Campaign campaign(Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        return Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                dailyBudget, startDate, endDate,
                NOW);
    }
}
