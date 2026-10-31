package com.lunchcatch.adserving;

import com.lunchcatch.IntegrationTestSupport;
import com.lunchcatch.adserving.contract.ImpressionServedEvent;
import com.lunchcatch.adserving.contract.SwipedEvent;
import com.lunchcatch.adserving.engine.service.CandidateCache;
import com.lunchcatch.adserving.feed.service.MemberInfoCache;
import com.lunchcatch.billing.contract.BudgetQueryService;
import com.lunchcatch.billing.contract.PointDeductor;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.campaign.contract.StoreAudienceUpdater;
import com.lunchcatch.member.contract.MemberQueryService;
import com.lunchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
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

    @Autowired private CandidateCache candidateCache;
    @Autowired private MemberInfoCache memberInfoCache;

    @AfterEach
    void clearFunnelEvents() {
        funnelEvents.clear();
    }

    /*
     * 테스트는 그날 후보를 DB 에 바로 넣어 변경 알림이 없고 회원 조회를 테스트마다 다르게 꾸민다.
     * 앞 테스트가 메모리에 둔 후보와 회원을 비워 처음부터 읽게 한다.
     */
    @BeforeEach
    void clearMemoryCaches() {
        ReflectionTestUtils.setField(candidateCache, "snapshot", null);
        ((Map<?, ?>) ReflectionTestUtils.getField(memberInfoCache, "entries")).clear();
        // 필터와 30일 찜의 캐시도 비운다. 테스트가 DB 에 바로 넣은 필터와 스와이프를 피드가 읽게 한다
        for (String pattern : List.of("adserving:feedFilter:*", "adserving:feedWishes:*")) {
            Set<String> cached = redisTemplate.keys(pattern);
            if (cached != null && !cached.isEmpty()) {
                redisTemplate.delete(cached);
            }
        }
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
         * 노출 저장 큐는 묶음 트랜잭션 안에서 이벤트를 발행한다. 이 수신자는 그 자리에서 돌아 묶음 저장 트랜잭션을 실패시킨다.
         * 묶음 저장이 롤백되는 경우를 재현하려는 것이다.
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
