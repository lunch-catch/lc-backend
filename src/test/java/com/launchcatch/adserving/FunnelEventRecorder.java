package com.launchcatch.adserving;

import com.launchcatch.adserving.contract.ImpressionServedEvent;
import com.launchcatch.adserving.contract.SwipedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * 분석이 받기로 한 조건(AFTER_COMMIT, fallbackExecution)으로 퍼널 이벤트를 모은다.
 * 분석의 @Async 는 빼고 같은 스레드에서 받아 단언이 기다리지 않게 한다.
 */
class FunnelEventRecorder {

    private final List<Object> events = new CopyOnWriteArrayList<>();

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
    }
}
