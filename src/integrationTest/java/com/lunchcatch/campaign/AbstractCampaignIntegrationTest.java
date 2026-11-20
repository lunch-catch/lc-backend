package com.lunchcatch.campaign;

import com.lunchcatch.IntegrationTestSupport;
import com.lunchcatch.campaign.poster.client.PosterImageInspector;
import com.lunchcatch.campaign.repository.CampaignRepository;
import com.lunchcatch.campaign.service.CampaignCouponConditionService;
import com.lunchcatch.campaign.template.SlowCallMeasureSettings.Mode;
import com.lunchcatch.campaign.template.client.SlowBedrockRuntimeClient;
import java.time.Clock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/*
 * campaign 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 둔다. 컨텍스트 캐시 키는 빈 오버라이드와 설정의
 * 집합으로 갈리므로 그 집합을 여기 모은다. 컨텍스트 하나가 힙 20~25MB 를 상주로 쓴다(#277).
 *
 * 스파이 빈은 전부 스파이다. 목으로 두면 스텁하지 않은 테스트가 null 을 받아 깨진다. 스파이는 기본적으로
 * 실제 구현에 위임하고, 경쟁이나 장애를 만들어야 하는 테스트만 그 호출을 스텁한다.
 *
 * 앱을 실제 포트로 띄운다. 느린 모델 호출 측정(#1269)이 톰캣의 작업 스레드를 재야 하는데 MockMvc 는 그 스레드를
 * 거치지 않는다. MockMvc 를 쓰는 테스트는 그대로 돈다.
 *
 * bedrock.model-id 를 줘서 FakeTemplateHtmlGenerator 대신 실제 BedrockTemplateHtmlGenerator 를 켜고, 그 안에서
 * 부르는 Bedrock 클라이언트만 스텁으로 바꾼다. 벌크헤드가 실제 생성기 안에 있어 측정이 그것을 거쳐야 한다. 스텁은
 * 기본으로 지연 없이 슬롯 검증을 통과하는 HTML 을 주므로 다른 테스트에는 Fake 를 쓸 때와 같다. 지연을 바꾼
 * 테스트는 끝날 때 기본으로 되돌린다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "bedrock.model-id=campaign-integration-test-stub",
                "bedrock.region=ap-northeast-1"
        })
@AutoConfigureMockMvc
@Transactional
public abstract class AbstractCampaignIntegrationTest extends IntegrationTestSupport {

    protected static final SlowBedrockRuntimeClient BEDROCK_STUB = new SlowBedrockRuntimeClient(0L, Mode.SINGLE);

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

    @TestBean(methodName = "bedrockStub")
    protected BedrockRuntimeClient bedrockRuntimeClient;

    static BedrockRuntimeClient bedrockStub() {
        return BEDROCK_STUB;
    }
}
