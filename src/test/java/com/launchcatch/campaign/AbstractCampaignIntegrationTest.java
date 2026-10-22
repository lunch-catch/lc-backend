package com.launchcatch.campaign;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.campaign.poster.client.PosterImageInspector;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import java.time.Clock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/*
 * campaign 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 둔다. 컨텍스트 캐시 키는 빈 오버라이드와 설정의
 * 집합으로 갈리므로 그 집합을 여기 모은다. 컨텍스트 하나가 힙 20~25MB 를 상주로 쓴다(#277).
 *
 * 전부 스파이다. 목으로 두면 스텁하지 않은 테스트가 null 을 받아 깨진다. 스파이는 기본적으로
 * 실제 구현에 위임하고, 경쟁이나 장애를 만들어야 하는 테스트만 그 호출을 스텁한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public abstract class AbstractCampaignIntegrationTest extends IntegrationTestSupport {

    @MockitoSpyBean
    protected Clock clock;

    @MockitoSpyBean
    protected CampaignRepository campaignRepository;

    @MockitoSpyBean
    protected CampaignCouponConditionService campaignCouponConditionService;

    /*
     * 실제 구현은 S3 와 Rekognition 을 부른다.
     * 검수 통합 테스트는 이 호출을 스텁한다(#535).
     */
    @MockitoSpyBean
    protected PosterImageInspector posterImageInspector;
}
