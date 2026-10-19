package com.launchcatch.member;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.member.client.KakaoUnlinkClient;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/*
 * member 통합 테스트가 공유하는 컨텍스트다.
 * @SpringBootTest 선언과 빈 오버라이드를 이 도메인에서 하나로 모아 테스트 파일이 여럿이어도 컨텍스트는 하나다.
 * 카카오 외부 호출은 목으로 둔다. 스텁하지 않은 테스트는 카카오를 부르지 않는 경로만 지난다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractMemberIntegrationTest extends IntegrationTestSupport {

    @MockitoBean protected KakaoIdTokenExchanger exchanger;
    @MockitoBean protected KakaoUnlinkClient unlink;
}
