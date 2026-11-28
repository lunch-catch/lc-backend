package com.lunchcatch.adserving.engine.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.engine.service.CandidateCache;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class CandidateChangeSubscriberTest {

    private static final Duration RETRY = Duration.ofMillis(20);

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

        subscriber.candidateChangeSubscription(container).onApplicationEvent(readyEvent());

        verify(container).start();
    }

    @Test
    @DisplayName("구독이 맺어지지 않아도 예외를 올리지 않는다")
    void subscriptionFailureDoesNotPropagate() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doThrow(new IllegalStateException("Subscription registration timeout exceeded")).when(container).start();

        assertThatCode(() -> subscriber.candidateChangeSubscription(container)
                .onApplicationEvent(readyEvent()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("처음 맺기에 실패하면 멈췄다 다시 시작해 구독을 맺는다")
    void retriesAfterFirstFailure() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doThrow(new IllegalStateException("connection refused")).doNothing().when(container).start();
        when(container.isListening()).thenReturn(false, true);

        CandidateChangeSubscriber.subscribe(container, RETRY, () -> false);

        verify(container, timeout(2000).times(2)).start();
        verify(container, timeout(2000)).stop();
        verify(container, after(300).times(2)).start();
    }

    @Test
    @DisplayName("앱이 닫히는 중이면 다시 시작하지 않는다")
    void doesNotRetryWhileClosing() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doThrow(new IllegalStateException("connection refused")).when(container).start();

        CandidateChangeSubscriber.subscribe(container, RETRY, () -> true);

        verify(container, after(300).never()).stop();
    }

    private static ApplicationReadyEvent readyEvent() {
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        when(event.getApplicationContext()).thenReturn(mock(ConfigurableApplicationContext.class));
        return event;
    }
}
