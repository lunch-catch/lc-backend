package com.launchcatch.coupon.service;

import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.scheduler.TransientFailures;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class CouponFunnelBatchService {

    // 운영 측정 전까지 트랜잭션당 쿠폰과 커밋 콜백을 500개로 제한한다.
    private static final int PAGE_SIZE = 500;
    private static final PageRequest PAGE = PageRequest.of(0, PAGE_SIZE);
    private final CouponEventRepository events;
    private final MemberCouponRepository coupons;
    private final CouponFunnelEventPublisher publisher;
    private final BatchExecutionService executions;
    private final RetryBackoff backoff;
    private final TransactionTemplate transactions;

    public CouponFunnelBatchService(CouponEventRepository events, MemberCouponRepository coupons,
            CouponFunnelEventPublisher publisher, BatchExecutionService executions, RetryBackoff backoff,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.coupons = coupons;
        this.publisher = publisher;
        this.executions = executions;
        this.backoff = backoff;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void open(LocalDate businessDate) {
        long afterId = 0;
        List<CouponEvent> page;
        do {
            page = events.findByBusinessDateAndStatusAndIdGreaterThanOrderByIdAsc(
                    businessDate, CouponEventStatus.ACTIVE, afterId, PAGE);
            for (CouponEvent event : page) {
                run("COUPON_OPEN_" + event.getCampaignId(), businessDate,
                        () -> openOne(event.getId(), businessDate));
                afterId = event.getId();
            }
        } while (page.size() == PAGE_SIZE);
    }

    public void expire(LocalDateTime now) {
        long afterId = 0;
        List<CouponEvent> page;
        do {
            page = events.findByUsableEndAtLessThanEqualAndIdGreaterThanOrderByIdAsc(now, afterId, PAGE);
            for (CouponEvent event : page) {
                String jobName = "COUPON_EXPIRE_" + event.getCampaignId();
                run(jobName, event.getBusinessDate(), () -> expireOne(event, now, jobName));
                afterId = event.getId();
            }
        } while (page.size() == PAGE_SIZE);
    }

    private void openOne(long eventId, LocalDate businessDate) {
        transactions.executeWithoutResult(status -> events.findByIdForUpdate(eventId)
                .filter(event -> event.getStatus() == CouponEventStatus.ACTIVE
                        && event.getBusinessDate().equals(businessDate))
                .ifPresent(event -> publisher.opened(event, businessDate.atTime(11, 0))));
    }

    private void expireOne(CouponEvent event, LocalDateTime now, String jobName) {
        Long afterId = 0L;
        while (afterId != null) {
            if (!executions.renew(jobName, event.getBusinessDate())) {
                throw new IllegalStateException("쿠폰 만료 작업의 점유를 잃었다.");
            }
            long cursor = afterId;
            afterId = transactions.execute(status -> expirePage(event, now, cursor));
        }
    }

    private Long expirePage(CouponEvent event, LocalDateTime now, long afterId) {
        List<MemberCoupon> page = coupons.findExpirationPage(event.getId(), now, afterId, PAGE);
        for (MemberCoupon coupon : page) {
            coupon.changeToExpired(now);
            publisher.expired(event, coupon);
        }
        return page.size() == PAGE_SIZE ? page.getLast().getId() : null;
    }

    private void run(String jobName, LocalDate businessDate, Runnable work) {
        if (executions.begin(jobName, businessDate) != StepOutcome.STARTED) {
            return;
        }
        int attempt = 1;
        while (true) {
            try {
                work.run();
                executions.succeed(jobName, businessDate);
                return;
            } catch (RuntimeException failure) {
                String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                if (!TransientFailures.isTransient(failure)
                        || !executions.recordRetry(jobName, businessDate, reason)
                        || !backoff.pause(attempt++)) {
                    executions.fail(jobName, businessDate, reason);
                    log.error("event=COUPON_FUNNEL_BATCH_FAILED jobName={} businessDate={}",
                            jobName, businessDate, failure);
                    return;
                }
                if (!executions.renew(jobName, businessDate)) {
                    return;
                }
            }
        }
    }
}
