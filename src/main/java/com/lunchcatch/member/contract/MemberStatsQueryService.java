package com.lunchcatch.member.contract;

import java.time.LocalDate;

public interface MemberStatsQueryService {

    MemberDailyStats dailyStats(LocalDate businessDate);
}
