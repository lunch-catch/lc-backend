package com.launchcatch.store;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.store.repository.OwnerTermsAgreementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * store 통합 테스트의 컨텍스트 설정과 빈 오버라이드를 한 곳에 모은다.
 * DB·캐시 컨테이너와 접속 프로퍼티는 IntegrationTestSupport에서 공유한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractStoreIntegrationTest extends IntegrationTestSupport {
    @Autowired protected MockMvc mvc;
    @MockitoSpyBean protected OwnerTermsAgreementRepository agreements;
    @MockitoBean protected S3Presigner s3Presigner;
    @MockitoBean protected S3Client s3Client;
}
