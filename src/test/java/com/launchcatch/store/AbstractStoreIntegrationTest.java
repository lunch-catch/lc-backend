package com.launchcatch.store;

import com.launchcatch.IntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;

// store 통합 테스트의 Spring 설정을 한 곳에 모으며 DB·캐시 컨테이너는 IntegrationTestSupport에서 공유한다.
@SpringBootTest
abstract class AbstractStoreIntegrationTest extends IntegrationTestSupport { }