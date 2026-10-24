package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.campaign.AbstractCampaignIntegrationTest;
import com.launchcatch.campaign.contract.CampaignQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * findByIdForUpdate 는 트랜잭션 안에서만 부를 수 있다(#876). 행 잠금은 트랜잭션이 끝날 때 풀리므로 트랜잭션 없이
 * 부르면 잠갔다는 보장이 없다. 클래스 단위 @Transactional 을 NOT_SUPPORTED 로 덮어 실제로 트랜잭션 없는 호출을 만든다
 * (feedback_campaign_integration_test_transactional_pitfall). 없는 캠페인 ID 만 쓰므로 정리할 데이터가 없다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignQueryServiceMandatoryIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final long MISSING_CAMPAIGN_ID = 9_000_000_001L;

    @Autowired
    private CampaignQueryService campaignQueryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 트랜잭션_없이_부르면_거부한다() {
        assertThatThrownBy(() -> campaignQueryService.findByIdForUpdate(MISSING_CAMPAIGN_ID))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 트랜잭션_안에서_부르면_조회한다() {
        var result = new TransactionTemplate(transactionManager)
                .execute(status -> campaignQueryService.findByIdForUpdate(MISSING_CAMPAIGN_ID));

        assertThat(result).isEmpty();
    }
}
