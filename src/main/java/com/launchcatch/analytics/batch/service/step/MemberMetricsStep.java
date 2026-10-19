package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.analytics.batch.MemberMetrics;
import com.launchcatch.analytics.batch.repository.MemberAnalyticsRepository;
import com.launchcatch.analytics.batch.service.AggregationStep;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.member.contract.MemberDailyStats;
import com.launchcatch.member.contract.MemberStatsQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 회원 지표(A-01)의 일별 값을 모아 daily_member_analytics 의 그날 한 행으로 교체한다.
 *
 * 신규, 누적, 탈퇴는 MemberStatsQueryService 가 영업일 경계로 세어 준다. 활성 회원은 분석이 자기
 * event_log 에서 그날 이벤트가 기록된 회원을 중복 없이 센다.
 */
@Component
@Order(200)
public class MemberMetricsStep implements AggregationStep<MemberMetrics> {

    private final MemberStatsQueryService members;
    private final EventLogRepository eventLog;
    private final MemberAnalyticsRepository repository;
    private final Clock clock;

    public MemberMetricsStep(
            MemberStatsQueryService members,
            EventLogRepository eventLog,
            MemberAnalyticsRepository repository,
            Clock clock) {
        this.members = members;
        this.eventLog = eventLog;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "MemberMetricsStep";
    }

    @Override
    public MemberMetrics collect(LocalDate businessDate) {
        MemberDailyStats stats = members.dailyStats(businessDate);
        return new MemberMetrics(stats.newMemberCount(), stats.cumulativeMemberCount(),
                stats.withdrawnCount(), eventLog.countDistinctMembers(businessDate));
    }

    @Override
    public int publish(LocalDate businessDate, MemberMetrics collected) {
        repository.replace(businessDate, collected, LocalDateTime.now(clock));
        return 1;
    }
}
