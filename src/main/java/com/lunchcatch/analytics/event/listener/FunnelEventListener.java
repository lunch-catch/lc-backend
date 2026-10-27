package com.lunchcatch.analytics.event.listener;

import com.lunchcatch.adserving.contract.ImpressionServedEvent;
import com.lunchcatch.adserving.contract.SwipeAction;
import com.lunchcatch.adserving.contract.SwipedEvent;
import com.lunchcatch.analytics.event.EventRecord;
import com.lunchcatch.analytics.event.EventType;
import com.lunchcatch.analytics.event.service.FunnelEventRecorder;
import com.lunchcatch.coupon.contract.CouponExpiredEvent;
import com.lunchcatch.coupon.contract.CouponIssueOpenedEvent;
import com.lunchcatch.coupon.contract.CouponIssuedEvent;
import com.lunchcatch.coupon.contract.CouponRedeemedEvent;
import com.lunchcatch.global.config.ClockConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * 광고 서빙과 쿠폰이 발행한 퍼널 이벤트 6종을 받아 event_log 에 기록한다.
 *
 * 모든 수신 메서드는 AFTER_COMMIT 이다. 발행한 쪽의 트랜잭션이 롤백되면 이벤트도 받지 않는다. 일반
 * 이벤트 수신은 발행 즉시 실행되므로 롤백된 노출이 퍼널에 남는다.
 *
 * fallbackExecution 을 빠뜨리지 않는다. SwipedEvent 는 트랜잭션 밖에서 발행되고, 속성이 없으면 활성
 * 트랜잭션이 없을 때 수신 메서드를 아예 부르지 않아 스와이프 이벤트가 오류 없이 모두 사라진다. 트랜잭션
 * 안에서 발행하는 노출과 쿠폰은 속성이 있어도 같게 커밋 뒤에 받는다.
 *
 * 기록은 FunnelEventRecorder 의 전용 실행기가 맡는다. 이 클래스는 이벤트를 EventRecord 로 바꿔 넘기기만
 * 하고 예외를 만들지 않는다.
 */
@Component
public class FunnelEventListener {

    private final FunnelEventRecorder recorder;

    public FunnelEventListener(FunnelEventRecorder recorder) {
        this.recorder = recorder;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onImpressionServed(ImpressionServedEvent event) {
        recorder.record(new EventRecord(idempotencyKey(event.eventId()), EventType.IMPRESSION,
                instant(event.occurredAt()), event.memberId(), event.campaignId(), event.storeId(),
                event.serveId(), null, null, null));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSwiped(SwipedEvent event) {
        boolean wish = event.action() == SwipeAction.WISH;
        recorder.record(new EventRecord(idempotencyKey(event.eventId()), wish ? EventType.WISH : EventType.PASS,
                instant(event.occurredAt()), event.memberId(), event.campaignId(), event.storeId(),
                event.serveId(), wish ? event.wishId() : null, null, null));
    }

    /*
     * 발급 열림은 회원이 없고, event_log 의 CHECK 가 캠페인과 가게도 비우라고 한다. 이벤트가 싣고 오는
     * 캠페인과 가게는 쓰지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCouponIssueOpened(CouponIssueOpenedEvent event) {
        recorder.record(new EventRecord(idempotencyKey(event.eventId()), EventType.ISSUE_OPEN,
                instant(event.occurredAt()), null, null, null, null, null, event.couponEventId(), null));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCouponIssued(CouponIssuedEvent event) {
        recorder.record(new EventRecord(idempotencyKey(event.eventId()), EventType.ISSUE,
                instant(event.occurredAt()), event.memberId(), event.campaignId(), event.storeId(),
                null, null, event.couponEventId(), event.memberCouponId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCouponRedeemed(CouponRedeemedEvent event) {
        recorder.record(new EventRecord(idempotencyKey(event.eventId()), EventType.REDEEM,
                instant(event.occurredAt()), event.memberId(), event.campaignId(), event.storeId(),
                null, null, event.couponEventId(), event.memberCouponId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCouponExpired(CouponExpiredEvent event) {
        recorder.record(new EventRecord(idempotencyKey(event.eventId()), EventType.EXPIRE,
                instant(event.occurredAt()), event.memberId(), event.campaignId(), event.storeId(),
                null, null, event.couponEventId(), event.memberCouponId()));
    }

    /*
     * 이벤트의 eventId 는 "impression:{serveId}" 같은 문자열이고 EventRecord 의 멱등키는 UUID 다. 같은
     * 문자열에서 늘 같은 UUID 가 나오게 해서 같은 이벤트가 두 번 와도 한 건만 남긴다.
     */
    private static UUID idempotencyKey(String eventId) {
        return UUID.nameUUIDFromBytes(eventId.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant instant(LocalDateTime occurredAt) {
        return occurredAt.atZone(ClockConfig.ZONE).toInstant();
    }
}
