package com.launchcatch.store;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.store.repository.OwnerTermsAgreementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

// store 통합 테스트의 Spring 설정과 빈 오버라이드를 모으며 DB·캐시 컨테이너는 IntegrationTestSupport에서 공유한다.
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractStoreIntegrationTest extends IntegrationTestSupport {
    @Autowired protected MockMvc mvc;
    @MockitoSpyBean protected OwnerTermsAgreementRepository agreements;
}
