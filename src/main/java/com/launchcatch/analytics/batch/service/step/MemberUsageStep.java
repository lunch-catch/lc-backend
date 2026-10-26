package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.analytics.batch.MemberUsage;
import com.launchcatch.analytics.batch.repository.MemberUsageRepository;
import com.launchcatch.analytics.batch.service.AggregationStep;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.coupon.contract.CouponStatsQueryService;
import com.launchcatch.coupon.contract.DailyMemberCouponStats;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 회원 사용 통계(A-12)의 일별 값을 모아 daily_member_usage 의 그날 행으로 교체한다.
 *
 * 사용 수와 할인액은 쿠폰의 확정 기록(dailyMemberStats)에서 오고 redeem 이벤트로 세지 않는다. 찜 수는
 * 쿠폰 계약의 값을 쓰지 않고 분석 자기 event_log 의 wish 행을 센다. 두 원천을 member_id 로 합쳐
 * 사용도 찜도 없는 회원은 행을 쓰지 않는다.
 */
@Component
@Order(700)
public class MemberUsageStep implements AggregationStep<MemberUsage> {

    private final CouponStatsQueryService coupons;
    private final EventLogRepository eventLog;
    private final MemberUsageRepository repository;
    private final Clock clock;

    public MemberUsageStep(
            CouponStatsQueryService coupons,
            EventLogRepository eventLog,
            MemberUsageRepository repository,
            Clock clock) {
        this.coupons = coupons;
        this.eventLog = eventLog;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "MemberUsageStep";
    }

    @Override
    public MemberUsage collect(LocalDate businessDate) {
        Map<Long, MemberUsage.Row> byMember = new TreeMap<>();
        for (DailyMemberCouponStats stats : coupons.dailyMemberStats(businessDate)) {
            byMember.put(stats.memberId(),
                    new MemberUsage.Row(stats.memberId(), 0, stats.usedCount(), stats.discountAmount()));
        }
        eventLog.countWishesByMember(businessDate).forEach((memberId, wishCount) ->
                byMember.merge(memberId, new MemberUsage.Row(memberId, wishCount, 0, 0),
                        (used, wished) -> new MemberUsage.Row(
                                memberId, wishCount, used.redeemCount(), used.savedAmount())));
        List<MemberUsage.Row> rows = byMember.values().stream()
                .filter(row -> row.wishCount() > 0 || row.redeemCount() > 0 || row.savedAmount() > 0)
                .toList();
        return new MemberUsage(rows);
    }

    @Override
    public int publish(LocalDate businessDate, MemberUsage collected) {
        repository.replace(businessDate, collected.rows(), LocalDateTime.now(clock));
        return collected.rows().size();
    }
}
