package com.launchcatch.coupon.service;

import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.coupon.config.CouponProperties;
import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CouponExpirationService {

    private final CouponProperties properties;

    private final CouponBatchRepository batches;
    private final CouponEventRepository events;
    private final MemberCouponRepository coupons;
    private final BatchAttemptGuard guard;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CouponExpirationService(CouponProperties properties, CouponBatchRepository batches, CouponEventRepository events,
            MemberCouponRepository coupons, BatchAttemptGuard guard, ApplicationEventPublisher publisher,
            Clock clock, PlatformTransactionManager manager) {
        this.properties = properties;
        this.batches = batches;
        this.events = events;
        this.coupons = coupons;
        this.guard = guard;
        this.publisher = publisher;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public List<LocalDate> expiringDates() {
        return batches.expiringDates(LocalDateTime.now(clock));
    }

    /*
     * 실행 시작 시각을 조회 기준으로 고정하고 ID를 설정된 크기로 나누어 읽는다.
     * 쿠폰 한 건마다 커밋해 API가 쓰는 쿠폰 행 잠금을 오래 잡지 않는다.
     * 중간 실패 후 재시도하면 이미 EXPIRED인 쿠폰은 조회에서 빠져 남은 쿠폰만 처리한다.
     */
    public int expire(LocalDate date, int attemptNo) {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        long cursor = 0;
        int count = 0;
        List<Long> ids;
        do {
            ids = batches.expiringIds(date, cutoff, cursor);
            for (long id : ids) {
                count += Objects.requireNonNull(transactions.execute(status -> expireOne(date, attemptNo, id)));
                cursor = id;
            }
        } while (ids.size() == properties.batch().pageSize());
        return count;
    }

    /*
     * 목록 조회와 사용 확정도 같은 쿠폰 행을 잠그므로 잠금 뒤 상태와 종료 시각을 다시 확인한다.
     * 상태 변경과 이벤트 발행은 함께 커밋하며 커밋 전 실패하면 둘 다 되돌려 다음 시도에서 다시 처리한다.
     */
    private int expireOne(LocalDate date, int attemptNo, long id) {
        guard.assertCurrentAttempt(CouponBatchTask.EXPIRE.jobName(), date, attemptNo);
        MemberCoupon coupon = coupons.findByIdForUpdate(id).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        if (coupon.getStatus() != MemberCouponStatus.ISSUED || now.isBefore(coupon.getUsableEndAt())) {
            return 0;
        }
        coupon.changeToExpired(now);
        CouponEvent event = events.findById(coupon.getCouponEventId()).orElseThrow();
        publisher.publishEvent(new CouponExpiredEvent("coupon-expired:" + id, coupon.getUsableEndAt(),
                coupon.getMemberId(), event.getCampaignId(), event.getStoreId(), event.getId(), id));
        return 1;
    }
}
