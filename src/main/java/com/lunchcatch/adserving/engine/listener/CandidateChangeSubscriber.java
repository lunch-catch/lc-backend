package com.lunchcatch.adserving.engine.listener;

import com.lunchcatch.adserving.engine.CandidateChange;
import com.lunchcatch.adserving.engine.service.CandidateCache;
import java.nio.charset.StandardCharsets;
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

    private final CandidateCache candidateCache;

    @Bean
    RedisMessageListenerContainer candidateChangeListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(this, new ChannelTopic(CandidateChange.CHANNEL));
        container.setAutoStartup(false);
        return container;
    }

    // 연결 실패면 컨테이너가 뒤에서 5초마다 다시 맺는다. 그 밖의 실패는 다시 맺지 않고 5초 다시 읽기에 맡긴다
    @Bean
    ApplicationListener<ApplicationReadyEvent> candidateChangeSubscription(RedisMessageListenerContainer candidateChangeListenerContainer) {
        return event -> {
            try {
                candidateChangeListenerContainer.start();
            } catch (RuntimeException e) {
                log.warn("event=ADSERVING_CANDIDATE_SUBSCRIBE_FAILED fallback=RELOAD_WITHIN_5S", e);
            }
        };
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
