package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.MemberInvalidImpressionCount;
import com.launchcatch.analytics.batch.InvalidMetrics;
import com.launchcatch.analytics.batch.InvalidReasonCode;
import com.launchcatch.analytics.batch.repository.InvalidAnalyticsRepository;
import com.launchcatch.analytics.batch.service.AggregationStep;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 무효 노출(A-09)의 일별 값을 모아 daily_invalid_analytics 의 그날 행으로 교체한다.
 *
 * AdservingStatsQueryService 가 회원과 캠페인 조합마다 사유별 건수를 묶어 준다. 분석은 건수가 있는 사유마다
 * 한 행으로 옮긴다. 합계 행은 두지 않는다. 필터와 사유별 합계는 조회가 이 행들을 합산해서 낸다.
 */
@Component
@Order(500)
public class InvalidMetricsStep implements AggregationStep<InvalidMetrics> {

    private final AdservingStatsQueryService adserving;
    private final InvalidAnalyticsRepository repository;
    private final Clock clock;

    public InvalidMetricsStep(
            AdservingStatsQueryService adserving,
            InvalidAnalyticsRepository repository,
            Clock clock) {
        this.adserving = adserving;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "InvalidMetricsStep";
    }

    @Override
    public InvalidMetrics collect(LocalDate businessDate) {
        List<InvalidMetrics.Row> rows = new ArrayList<>();
        for (MemberInvalidImpressionCount count : adserving.memberInvalidCounts(businessDate)) {
            addIfPositive(rows, count, InvalidReasonCode.EXPIRED_OR_UNKNOWN, count.expiredOrUnknownCount());
            addIfPositive(rows, count, InvalidReasonCode.NOT_OWNER, count.notOwnerCount());
        }
        return new InvalidMetrics(List.copyOf(rows));
    }

    @Override
    public int publish(LocalDate businessDate, InvalidMetrics collected) {
        repository.replace(businessDate, collected.rows(), LocalDateTime.now(clock));
        return collected.rows().size();
    }

    private void addIfPositive(
            List<InvalidMetrics.Row> rows, MemberInvalidImpressionCount count, InvalidReasonCode reason, long amount) {
        if (amount > 0) {
            rows.add(new InvalidMetrics.Row(count.campaignId(), count.memberId(), reason, amount));
        }
    }
}
