package com.lunchcatch.adserving.engine.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lunchcatch.adserving.engine.service.CandidateCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class CandidateChangeSubscriberTest {

    private final CandidateChangeSubscriber subscriber = new CandidateChangeSubscriber(mock(CandidateCache.class));

    @Test
    @DisplayName("구독 컨테이너는 기동과 함께 뜨지 않는다")
    void containerDoesNotStartWithContext() {
        RedisMessageListenerContainer container =
                subscriber.candidateChangeListenerContainer(mock(RedisConnectionFactory.class));

        assertThat(container.isAutoStartup()).isFalse();
    }

    @Test
    @DisplayName("앱이 준비되면 구독을 시작한다")
    void startsSubscriptionWhenReady() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);

        subscriber.candidateChangeSubscription(container).onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(container).start();
    }

    @Test
    @DisplayName("구독이 맺어지지 않아도 예외를 올리지 않는다")
    void subscriptionFailureDoesNotPropagate() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doThrow(new IllegalStateException("Subscription registration timeout exceeded")).when(container).start();

        assertThatCode(() -> subscriber.candidateChangeSubscription(container)
                .onApplicationEvent(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
    }
}
