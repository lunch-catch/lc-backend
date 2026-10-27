package com.lunchcatch.analytics.event.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class FunnelEventListenerTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 17, 9, 30, 15);
    private static final Instant OCCURRED_INSTANT = Instant.parse("2026-10-17T00:30:15Z");
    private static final UUID SERVE_ID = UUID.fromString("0f8b0d6e-3a77-4ee5-9bd3-6af85b495830");

    @Mock
    private FunnelEventRecorder recorder;

    @InjectMocks
    private FunnelEventListener listener;

    private EventRecord recorded() {
        ArgumentCaptor<EventRecord> captor = ArgumentCaptor.forClass(EventRecord.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void 노출은_impression으로_기록하고_발생_시각을_KST에서_Instant로_바꾼다() {
        listener.onImpressionServed(new ImpressionServedEvent("impression:" + SERVE_ID, OCCURRED_AT, 7L, 11L, 13L, SERVE_ID));

        EventRecord record = recorded();

        assertThat(record.eventType()).isEqualTo(EventType.IMPRESSION);
        assertThat(record.occurredAt()).isEqualTo(OCCURRED_INSTANT);
        assertThat(record.memberId()).isEqualTo(7L);
        assertThat(record.campaignId()).isEqualTo(11L);
        assertThat(record.storeId()).isEqualTo(13L);
        assertThat(record.serveId()).isEqualTo(SERVE_ID);
        assertThat(record.wishId()).isNull();
        assertThat(record.couponEventId()).isNull();
        assertThat(record.memberCouponId()).isNull();
    }

    @Test
    void 스와이프_찜은_wish로_찜_id와_함께_기록한다() {
        listener.onSwiped(new SwipedEvent("swipe:" + SERVE_ID + ":7", OCCURRED_AT, 7L, 11L, 13L, SERVE_ID, SwipeAction.WISH, 99L));

        EventRecord record = recorded();

        assertThat(record.eventType()).isEqualTo(EventType.WISH);
        assertThat(record.wishId()).isEqualTo(99L);
        assertThat(record.serveId()).isEqualTo(SERVE_ID);
    }

    @Test
    void 스와이프_패스는_pass로_찜_id_없이_기록한다() {
        listener.onSwiped(new SwipedEvent("swipe:" + SERVE_ID + ":7", OCCURRED_AT, 7L, 11L, 13L, SERVE_ID, SwipeAction.PASS, null));

        EventRecord record = recorded();

        assertThat(record.eventType()).isEqualTo(EventType.PASS);
        assertThat(record.wishId()).isNull();
    }

    @Test
    void 발급_열림은_회원과_캠페인_가게_없이_쿠폰_이벤트만으로_기록한다() {
        listener.onCouponIssueOpened(new CouponIssueOpenedEvent("coupon-issue-open:5", OCCURRED_AT, 11L, 13L, 5L));

        EventRecord record = recorded();

        assertThat(record.eventType()).isEqualTo(EventType.ISSUE_OPEN);
        assertThat(record.memberId()).isNull();
        assertThat(record.campaignId()).isNull();
        assertThat(record.storeId()).isNull();
        assertThat(record.couponEventId()).isEqualTo(5L);
        assertThat(record.memberCouponId()).isNull();
    }

    @Test
    void 발급과_사용과_만료는_같은_member_coupon_id로_이어진다() {
        listener.onCouponIssued(new CouponIssuedEvent("coupon-issued:21", OCCURRED_AT, 7L, 11L, 13L, 5L, 21L));
        listener.onCouponRedeemed(new CouponRedeemedEvent("coupon-redeemed:21", OCCURRED_AT, 7L, 11L, 13L, 5L, 21L));
        listener.onCouponExpired(new CouponExpiredEvent("coupon-expired:21", OCCURRED_AT, 7L, 11L, 13L, 5L, 21L));

        ArgumentCaptor<EventRecord> captor = ArgumentCaptor.forClass(EventRecord.class);
        verify(recorder, org.mockito.Mockito.times(3)).record(captor.capture());
        List<EventRecord> records = captor.getAllValues();

        assertThat(records).extracting(EventRecord::eventType)
                .containsExactly(EventType.ISSUE, EventType.REDEEM, EventType.EXPIRE);
        assertThat(records).extracting(EventRecord::memberCouponId).containsOnly(21L);
        assertThat(records).extracting(EventRecord::couponEventId).containsOnly(5L);
    }

    @Test
    void 같은_이벤트_id는_같은_멱등키가_되고_다른_이벤트_id는_다른_멱등키가_된다() {
        listener.onImpressionServed(new ImpressionServedEvent("impression:a", OCCURRED_AT, 7L, 11L, 13L, SERVE_ID));
        listener.onImpressionServed(new ImpressionServedEvent("impression:a", OCCURRED_AT, 7L, 11L, 13L, SERVE_ID));
        listener.onImpressionServed(new ImpressionServedEvent("impression:b", OCCURRED_AT, 7L, 11L, 13L, SERVE_ID));

        ArgumentCaptor<EventRecord> captor = ArgumentCaptor.forClass(EventRecord.class);
        verify(recorder, org.mockito.Mockito.times(3)).record(captor.capture());
        List<EventRecord> records = captor.getAllValues();

        assertThat(records.get(0).eventId()).isEqualTo(records.get(1).eventId());
        assertThat(records.get(0).eventId()).isNotEqualTo(records.get(2).eventId());
    }

    /*
     * fallbackExecution 이 빠지면 트랜잭션 밖에서 발행하는 SwipedEvent 가 오류 없이 전부 사라진다. 통합 테스트
     * 없이 이 속성만 지키려고 애너테이션을 직접 읽는다.
     */
    @Test
    void 수신_메서드_여섯은_모두_커밋_뒤에_받고_트랜잭션_밖_발행도_받는다() {
        List<Method> handlers = Arrays.stream(FunnelEventListener.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(TransactionalEventListener.class))
                .toList();

        assertThat(handlers).hasSize(6);
        for (Method handler : handlers) {
            TransactionalEventListener annotation = handler.getAnnotation(TransactionalEventListener.class);
            assertThat(annotation.phase()).as(handler.getName()).isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(annotation.fallbackExecution()).as(handler.getName()).isTrue();
        }
    }
}
