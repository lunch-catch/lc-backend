package com.launchcatch.adserving.engine.listener;

import com.launchcatch.adserving.engine.CandidateChange;
import com.launchcatch.adserving.engine.service.CandidateCache;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        return container;
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
