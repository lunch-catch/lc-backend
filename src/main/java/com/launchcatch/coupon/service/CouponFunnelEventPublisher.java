package com.launchcatch.coupon.service;

import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.contract.CouponIssueOpenedEvent;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.contract.CouponRedeemedEvent;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.MemberCoupon;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
@RequiredArgsConstructor
public class CouponFunnelEventPublisher {

    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;

    public void opened(CouponEvent event, LocalDateTime openedAt) {
        afterCommit(new CouponIssueOpenedEvent("coupon-open:" + event.getId(), openedAt,
                event.getCampaignId(), event.getStoreId(), event.getId()));
    }

    public void issued(CouponEvent event, MemberCoupon coupon, LocalDateTime issuedAt) {
        afterCommit(new CouponIssuedEvent("coupon-issued:" + coupon.getId(), issuedAt,
                coupon.getMemberId(), event.getCampaignId(), event.getStoreId(), event.getId(), coupon.getId()));
    }

    public void redeemed(CouponEvent event, MemberCoupon coupon, LocalDateTime redeemedAt) {
        afterCommit(new CouponRedeemedEvent("coupon-redeemed:" + coupon.getId(), redeemedAt,
                coupon.getMemberId(), event.getCampaignId(), event.getStoreId(), event.getId(), coupon.getId()));
    }

    public void expired(CouponEvent event, MemberCoupon coupon) {
        afterCommit(new CouponExpiredEvent("coupon-expired:" + coupon.getId(), coupon.getUsableEndAt(),
                coupon.getMemberId(), event.getCampaignId(), event.getStoreId(), event.getId(), coupon.getId()));
    }

    private void afterCommit(Object event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("쿠폰 퍼널 이벤트는 업무 트랜잭션 안에서 등록해야 한다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                /*
                 * 퍼널 기록은 유실을 허용하고 리포트 건수는 원천 조회로 다시 센다.
                 * 분석 리스너 실패를 이미 커밋한 발급·사용의 실패로 바꾸지 않으며 outbox는 두지 않는다.
                 */
                try {
                    events.publishEvent(event);
                } catch (RuntimeException failure) {
                    String type = event.getClass().getSimpleName();
                    meters.counter("coupon.funnel.events.dropped", "type", type).increment();
                    log.warn("event=COUPON_FUNNEL_EVENT_DROPPED type={} payload={}", type, event, failure);
                }
            }
        });
    }
}
