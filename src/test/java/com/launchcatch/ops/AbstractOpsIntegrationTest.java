package com.launchcatch.ops;

import com.launchcatch.IntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/*
 * ops 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 둔다. 컨텍스트 캐시 키는 빈 오버라이드와 설정의
 * 집합으로 갈리므로 그 집합을 여기 모은다. 지금은 오버라이드가 없다. 생기면 여기에 더한다.
 *
 * @AutoConfigureMockMvc 는 배치 수동 재실행(#362)이 ops 의 첫 HTTP 입구라 더했다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractOpsIntegrationTest extends IntegrationTestSupport {
}
