package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CampaignLookupServiceTest {

    @Test
    void test1() {
        CampaignLookupService service = new CampaignLookupService();
        assertThat(service.hasActiveCampaign(2L)).isTrue();
        assertThat(service.hasActiveCampaign(3L)).isFalse();
        assertThat(service.hasActiveCampaign(null)).isFalse();
    }
}
