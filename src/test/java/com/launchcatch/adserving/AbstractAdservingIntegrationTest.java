package com.launchcatch.adserving;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.adserving.contract.ImpressionServedEvent;
import com.launchcatch.adserving.contract.SwipedEvent;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.StoreAudienceUpdater;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * adserving 통합 테스트의 @SpringBootTest 와 빈 오버라이드를 한 곳에 모은다. 컨텍스트 캐시 키가 이 집합으로 갈려서 테스트마다 나눠 적으면 컨텍스트가 늘어난다.
 * 다른 도메인은 contract 를 목으로 둔다. 회원과 정산의 실제 동작은 그 도메인의 테스트가 본다.
 * 시계는 서빙 시간대(10:00~12:59)를 재현하려고 스파이로 둔다. 스텁은 doReturn 으로 건다.
 * when(clock.instant()) 는 instant() 를 실제로 불러 마지막 호출로 기억하는데, 그사이 다른 스레드가 getZone() 을 부르면 그 스텁이 getZone() 에 걸린다(#379).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractAdservingIntegrationTest.FunnelEventRecorder.class)
abstract class AbstractAdservingIntegrationTest extends IntegrationTestSupport {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected StringRedisTemplate redisTemplate;
    @MockitoSpyBean protected Clock clock;
    @MockitoBean protected MemberQueryService memberQueryService;
    @MockitoBean protected BudgetQueryService budgetQueryService;
    @MockitoBean protected PointDeductor pointDeductor;
    @MockitoBean protected CampaignQueryService campaignQueryService;
    @MockitoBean protected StoreQueryService storeQueryService;
    @MockitoBean protected StoreAudienceUpdater storeAudienceUpdater;
    @Autowired protected FunnelEventRecorder funnelEvents;

    @AfterEach
    void clearFunnelEvents() {
        funnelEvents.clear();
    }

    /*
     * 분석이 받기로 한 조건(AFTER_COMMIT, fallbackExecution)으로 퍼널 이벤트를 모은다.
     * 분석의 @Async 는 빼고 같은 스레드에서 받아 단언이 기다리지 않게 한다.
     */
    static class FunnelEventRecorder {

        private final List<Object> events = new CopyOnWriteArrayList<>();
        private volatile Runnable duringImpressionPublish = () -> {
        };

        /*
         * 노출 서비스는 트랜잭션 안에서 이벤트를 발행한다. 이 수신자는 그 자리에서 돌아 노출 저장 트랜잭션을 붙잡거나 실패시킨다.
         * 같은 노출이 동시에 오는 경합(#781)과 저장이 롤백되는 경우를 재현하려는 것이다.
         */
        @EventListener
        void during(ImpressionServedEvent event) {
            duringImpressionPublish.run();
        }

        void whileImpressionPublishing(Runnable action) {
            duringImpressionPublish = action;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void on(ImpressionServedEvent event) {
            events.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void on(SwipedEvent event) {
            events.add(event);
        }

        <T> List<T> received(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }

        void clear() {
            events.clear();
            duringImpressionPublish = () -> {
            };
        }
    }
}
