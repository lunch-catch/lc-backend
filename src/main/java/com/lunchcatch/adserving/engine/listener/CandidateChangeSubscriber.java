package com.lunchcatch.adserving.engine.listener;

import com.lunchcatch.adserving.engine.CandidateChange;
import com.lunchcatch.adserving.engine.service.CandidateCache;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/*
 * 다른 서버가 바꾼 그날 후보를 받아 이 서버의 메모리(CandidateCache)에 반영한다(#1042). 후보를 바꾼 서버도 자기 알림을 받는다.
 * 구독 컨테이너를 광고 서빙 안에 둔다. 공용 설정에 두면 다른 도메인과 함께 쓰는 파일을 고치게 된다.
 * 반영이 실패해도 예외를 올리지 않는다. 메모리는 5초 뒤 통째로 다시 읽어 맞춰진다.
 * 구독은 앱이 준비된 뒤 맺는다. 컨테이너는 구독을 2초 기다리고 넘으면 예외를 던지는데, 기동과 함께 띄우면
 * Valkey 가 잠깐 늦은 것만으로 앱이 뜨지 못한다(#1381). 없어도 5초 다시 읽기로 맞춰지는 길이 기동을 막지 않게 한다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CandidateChangeSubscriber implements MessageListener {

    // 메모리를 통째로 다시 읽는 주기와 맞춘다. 구독이 없는 동안에도 그만큼은 늦지 않는다
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private final CandidateCache candidateCache;

    @Bean
    RedisMessageListenerContainer candidateChangeListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(this, new ChannelTopic(CandidateChange.CHANNEL));
        container.setAutoStartup(false);
        return container;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> candidateChangeSubscription(RedisMessageListenerContainer candidateChangeListenerContainer) {
        return event -> subscribe(candidateChangeListenerContainer, RETRY_INTERVAL, event.getApplicationContext()::isClosed);
    }

    /*
     * 처음 맺기에 실패하면 컨테이너는 스스로 다시 시도하지 않는다. 연결을 얻지 못한 첫 시도는 재시도 경로를 타지 않고,
     * 실패한 뒤에도 시작된 것으로 남아 start() 를 다시 불러도 아무 일도 하지 않는다(Spring Data Redis 4.0.4).
     * 그래서 맺어질 때까지 멈췄다 다시 시작한다. 맺은 뒤 끊기면 컨테이너가 스스로 다시 맺는다.
     * 앱이 닫히는 중이면 멈춘 컨테이너를 다시 띄우지 않게 그만둔다.
     */
    static void subscribe(RedisMessageListenerContainer container, Duration interval, BooleanSupplier closing) {
        try {
            container.start();
            return;
        } catch (RuntimeException e) {
            log.warn("event=ADSERVING_CANDIDATE_SUBSCRIBE_FAILED retryIn={} fallback=RELOAD_WITHIN_5S", interval, e);
        }
        ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("candidate-subscribe-retry").daemon(true).factory());
        retry.scheduleWithFixedDelay(() -> {
            if (closing.getAsBoolean()) {
                retry.shutdown();
                return;
            }
            if (container.isListening()) {
                log.info("event=ADSERVING_CANDIDATE_SUBSCRIBE_RECOVERED");
                retry.shutdown();
                return;
            }
            container.stop();
            try {
                container.start();
            } catch (RuntimeException e) {
                // 첫 실패를 WARN 으로 남겼다. Valkey 가 멈춘 동안 주기마다 WARN 을 쌓지 않는다
                log.debug("event=ADSERVING_CANDIDATE_SUBSCRIBE_RETRY_FAILED retryIn={}", interval, e);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            candidateCache.apply(CandidateChange.parse(body));
        } catch (RuntimeException e) {
            log.warn("event=ADSERVING_CANDIDATE_CHANGE_APPLY_FAILED change={} fallback=RELOAD_WITHIN_5S", body, e);
        }
    }
}
