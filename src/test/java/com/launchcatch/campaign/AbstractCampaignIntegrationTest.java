package com.launchcatch.campaign;

import com.launchcatch.IntegrationTestSupport;
import java.time.Clock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/*
 * campaign 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 둔다. 컨텍스트 캐시 키는 빈 오버라이드와 설정의
 * 집합으로 갈리므로 그 집합을 여기 모은다. 컨텍스트 하나가 힙 20~25MB 를 상주로 쓴다(#277).
 *
 * Clock 은 목이 아니라 스파이다. 목으로 두면 스텁하지 않은 테스트가 null 을 받아 감사 필드가
 * 깨진다. 스파이는 기본적으로 실제 시각에 위임하고 시각을 고정해야 하는 테스트만 스텁한다.
 */
@SpringBootTest
@Transactional
public abstract class AbstractCampaignIntegrationTest extends IntegrationTestSupport {

    @MockitoSpyBean
    protected Clock clock;
}
