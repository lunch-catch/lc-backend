package com.launchcatch.member.service;

import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberDailyStats;
import com.launchcatch.member.contract.MemberStatsQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.repository.MemberRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberStatsQueryServiceImpl implements MemberStatsQueryService {

    private final MemberRepository memberRepository;

    @Override
    public MemberDailyStats dailyStats(LocalDate businessDate) {
        LocalDateTime dayStart = businessDate.atStartOfDay(ClockConfig.ZONE).toLocalDateTime();
        LocalDateTime nextDayStart = businessDate.plusDays(1).atStartOfDay(ClockConfig.ZONE).toLocalDateTime();

        long newMemberCount = memberRepository
                .countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(dayStart, nextDayStart);
        long cumulativeMemberCount = memberRepository.countByCreatedAtLessThan(nextDayStart);
        long withdrawnCount = memberRepository
                .countByStatusAndWithdrawnAtLessThan(MemberStatus.WITHDRAWN, nextDayStart);
        long suspendedCount = memberRepository
                .countByStatusAndSuspendedAtLessThan(MemberStatus.SUSPENDED, nextDayStart);

        return new MemberDailyStats(newMemberCount, cumulativeMemberCount, withdrawnCount, suspendedCount);
    }
}
