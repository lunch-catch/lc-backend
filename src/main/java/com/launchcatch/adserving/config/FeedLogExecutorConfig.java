package com.launchcatch.adserving.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
 * 피드 응답 뒤에 serve_log 와 접속 위치를 쓰는 대기열이다.
 *
 * 크기와 찼을 때의 처리는 설계서에 없어 #236 에서 임시로 정했다. 크기는 1000 이고 차면 기다리지 않고 버린다.
 * 기다리면 피드 응답이 DB 쓰기만큼 늦어지고, 버린 건수는 FeedLogWriter 가 지표로 센다.
 */
@Configuration
public class FeedLogExecutorConfig {

    public static final String FEED_LOG_EXECUTOR = "feedLogExecutor";

    private static final int THREADS = 2;
    private static final int QUEUE_CAPACITY = 1000;
    private static final int SHUTDOWN_WAIT_SECONDS = 10;

    @Bean(FEED_LOG_EXECUTOR)
    public ThreadPoolTaskExecutor feedLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("feed-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 배포로 내려갈 때 대기열에 남은 기록을 마저 쓴다
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        return executor;
    }
}
