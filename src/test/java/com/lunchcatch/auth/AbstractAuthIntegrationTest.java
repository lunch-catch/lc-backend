package com.lunchcatch.auth;

import com.lunchcatch.IntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/*
 * auth 통합 테스트가 공유하는 컨텍스트다.
 * @SpringBootTest 선언을 이 모듈에서 하나로 모아 테스트 파일이 여럿이어도 컨텍스트는 하나다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractAuthIntegrationTest extends IntegrationTestSupport {
}
