package com.launchcatch.adserving;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.member.contract.MemberQueryService;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/*
 * adserving 통합 테스트의 @SpringBootTest 와 빈 오버라이드를 한 곳에 모은다. 컨텍스트 캐시 키가 이 집합으로 갈려서 테스트마다 나눠 적으면 컨텍스트가 늘어난다.
 * 다른 도메인은 contract 를 목으로 둔다. 회원과 정산의 실제 동작은 그 도메인의 테스트가 본다.
 * 시계는 서빙 시간대(10:00~12:59)를 재현하려고 스파이로 둔다. 스텁은 doReturn 으로 건다.
 * when(clock.instant()) 는 instant() 를 실제로 불러 마지막 호출로 기억하는데, 그사이 다른 스레드가 getZone() 을 부르면 그 스텁이 getZone() 에 걸린다(#379).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FunnelEventRecorder.class)
abstract class AbstractAdservingIntegrationTest extends IntegrationTestSupport {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected StringRedisTemplate redisTemplate;
    @MockitoSpyBean protected Clock clock;
    @MockitoBean protected MemberQueryService memberQueryService;
    @MockitoBean protected BudgetQueryService budgetQueryService;
    @MockitoBean protected PointDeductor pointDeductor;
    @Autowired protected FunnelEventRecorder funnelEvents;

    @AfterEach
    void clearFunnelEvents() {
        funnelEvents.clear();
    }
}
