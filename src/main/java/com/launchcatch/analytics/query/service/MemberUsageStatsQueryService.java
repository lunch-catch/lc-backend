package com.launchcatch.analytics.query.service;

import com.launchcatch.analytics.query.MemberUsageStats;
import com.launchcatch.analytics.query.repository.MemberUsageQueryRepository;
import org.springframework.stereotype.Service;

/*
 * A-12 회원 사용 통계다. 본인의 daily_member_usage 전체 행을 합산한다. 다른 도메인을 부르지 않는다.
 * 전날까지 확정된 일별 값의 합이라 오늘 쓴 쿠폰은 다음 날 반영된다.
 */
@Service
public class MemberUsageStatsQueryService {

    private final MemberUsageQueryRepository repository;

    public MemberUsageStatsQueryService(MemberUsageQueryRepository repository) {
        this.repository = repository;
    }

    public MemberUsageStats query(long memberId) {
        return repository.sumByMember(memberId);
    }
}
