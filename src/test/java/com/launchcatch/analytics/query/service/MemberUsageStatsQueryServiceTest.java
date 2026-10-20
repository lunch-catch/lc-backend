package com.launchcatch.analytics.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.query.repository.MemberUsageQueryRepository;
import com.launchcatch.analytics.query.MemberUsageStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberUsageStatsQueryServiceTest {

    @Mock
    private MemberUsageQueryRepository repository;

    @Test
    void 본인_행의_합을_그대로_내린다() {
        when(repository.sumByMember(7L)).thenReturn(new MemberUsageStats(12, 5, 18000));

        MemberUsageStats stats = new MemberUsageStatsQueryService(repository).query(7L);

        assertThat(stats).isEqualTo(new MemberUsageStats(12, 5, 18000));
    }

    @Test
    void 행이_없으면_세_값이_0이다() {
        when(repository.sumByMember(7L)).thenReturn(new MemberUsageStats(0, 0, 0));

        MemberUsageStats stats = new MemberUsageStatsQueryService(repository).query(7L);

        assertThat(stats).isEqualTo(new MemberUsageStats(0, 0, 0));
    }
}
