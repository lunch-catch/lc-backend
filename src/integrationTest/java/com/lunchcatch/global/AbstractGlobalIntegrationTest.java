package com.lunchcatch.global;

import com.lunchcatch.IntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;

/*
 * global 통합 테스트가 공유하는 컨텍스트다.
 * @SpringBootTest 선언을 이 패키지에서 하나만 둔다. 컨텍스트 캐시 키가 갈리지 않게 빈 오버라이드도 여기 모은다.
 */
@SpringBootTest
public abstract class AbstractGlobalIntegrationTest extends IntegrationTestSupport {
}
