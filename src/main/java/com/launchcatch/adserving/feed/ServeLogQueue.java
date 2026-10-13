package com.launchcatch.adserving.feed;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/*
 * 피드 응답 뒤 serve_log 와 feed_visit_daily 를 쓰는 대기열이다(feed-api-design.md).
 *
 * 크기를 묶고 차면 버린다. 기다리게 하면 DB 가 느려질 때 피드 응답까지 늦어진다(#187).
 * 버린 건은 부른 쪽이 지표로 센다. 세지 않으면 서빙 로그 유실률을 잴 수 없다.
 * 피드 500 TPS 면 초당 500 건이 들어온다. 대기열 10,000 은 DB 가 20초 멈춰도 버티는 크기다.
 *
 * 실행기를 빈으로 등록하지 않고 안에 둔다. Executor 빈이 하나라도 있으면 Boot 가 applicationTaskExecutor 를 만들지 않는다.
 */
@Component
public class ServeLogQueue implements DisposableBean {

    private static final int THREADS = 2;
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int SHUTDOWN_WAIT_SECONDS = 10;

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public ServeLogQueue() {
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("serve-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        executor.initialize();
    }

    /*
     * 대기열에 넣는다.
     * 차서 넣지 못하면 false 다
     */
    public boolean offer(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (TaskRejectedException e) {
            return false;
        }
    }

    // 내려갈 때 남은 쓰기를 마치고 내려간다
    @Override
    public void destroy() {
        executor.shutdown();
    }
}
