package com.lunchcatch.billing;

import com.lunchcatch.IntegrationTestSupport;
import com.lunchcatch.billing.client.CommitCheckingGateway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * billing 통합 테스트가 공유하는 컨텍스트다.
 *
 * @SpringBootTest 선언을 이 도메인에서 하나만 둔다. Spring 의 컨텍스트 캐시 키는 클래스 수가
 * 아니라 빈 오버라이드와 설정의 집합으로 갈리므로, 그 집합을 여기 모으면 테스트 파일이 여럿이어도
 * 컨텍스트는 하나다. 컨텍스트 하나가 힙 20~25MB 를 상주로 쓴다(#277).
 *
 * 결제대행사만 가짜로 바꾼다. 전에는 두 테스트가 각각 @Primary 로 다른 가짜를 등록해 컨텍스트가
 * 둘로 갈렸다. CommitCheckingGateway 가 FakePaymentGateway 를 확장한 상위집합이라 그것 하나만
 * 등록해도 부모의 API 만 쓰는 테스트가 그대로 돈다.
 *
 * 그 설정을 classes 로 적어 준다. 중첩한 @TestConfiguration 은 상속되지 않는다. 스프링이 중첩
 * 설정을 찾는 대상은 실제로 실행하는 테스트 클래스라 기반 클래스에 두기만 하면 빠진다. 여기 적은
 * 것이 테스트 설정뿐이라 스프링 부트는 @SpringBootApplication 도 함께 찾아 담는다.
 */
@SpringBootTest(classes = AbstractBillingIntegrationTest.GatewayConfig.class)
@AutoConfigureMockMvc
abstract class AbstractBillingIntegrationTest extends IntegrationTestSupport {

    @Autowired private CommitCheckingGateway gateway;

    /*
     * 가짜 결제대행사도 이제 테스트 클래스 여럿이 함께 쓴다. 앞 테스트가 심어 둔 실패와 커밋
     * 확인 스위치를 테스트마다 되돌린다. 기반 클래스의 @BeforeEach 가 먼저 돌므로 켜야 하는
     * 테스트는 자기 @BeforeEach 에서 다시 켠다.
     */
    @BeforeEach
    void 결제대행사를_되돌린다() {
        gateway.failConfirmWith(null).failInquiryWith(null);
        gateway.clearSeen();
    }

    @TestConfiguration
    static class GatewayConfig {

        @Bean
        @Primary
        CommitCheckingGateway commitCheckingGateway(JdbcTemplate jdbc) {
            return new CommitCheckingGateway(jdbc);
        }
    }
}
