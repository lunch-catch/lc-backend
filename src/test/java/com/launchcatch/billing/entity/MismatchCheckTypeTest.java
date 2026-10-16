package com.launchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MismatchCheckTypeTest {

    @Test
    void 캠페인_소진_대사와_초과_소진은_캠페인을_가리키고_결제_대사는_결제를_가리킨다() {
        // 조정이 대사 건의 점주를 확인할 때(G-4) 대상 ID 를 캠페인으로 볼지 결제로 볼지가 이 값에 달려 있다
        assertThat(MismatchCheckType.CAMPAIGN_SPEND_VS_LEDGER.targetsCampaign()).isTrue();
        assertThat(MismatchCheckType.CAMPAIGN_OVERSPEND.targetsCampaign()).isTrue();
        assertThat(MismatchCheckType.PG_VS_CHARGE.targetsCampaign()).isFalse();
    }
}
