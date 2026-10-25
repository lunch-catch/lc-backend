package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.member.contract.MemberDailyStats;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.repository.MemberRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberStatsQueryServiceImplTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberStatsQueryServiceImpl memberStatsQueryService;

    @Test
    void KST_영업일_경계로_신규_누적_탈퇴_정지_회원을_조회한다() {
        LocalDate businessDate = LocalDate.of(2026, 10, 15);
        LocalDateTime dayStart = LocalDateTime.of(2026, 10, 15, 0, 0);
        LocalDateTime nextDayStart = LocalDateTime.of(2026, 10, 16, 0, 0);
        when(memberRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(dayStart, nextDayStart))
                .thenReturn(2L);
        when(memberRepository.countByCreatedAtLessThan(nextDayStart)).thenReturn(10L);
        when(memberRepository.countByStatusAndWithdrawnAtLessThan(MemberStatus.WITHDRAWN, nextDayStart))
                .thenReturn(3L);
        when(memberRepository.countByStatusAndSuspendedAtLessThan(MemberStatus.SUSPENDED, nextDayStart))
                .thenReturn(4L);

        MemberDailyStats result = memberStatsQueryService.dailyStats(businessDate);

        assertThat(result).isEqualTo(new MemberDailyStats(2L, 10L, 3L, 4L));
        verify(memberRepository).countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(dayStart, nextDayStart);
        verify(memberRepository).countByCreatedAtLessThan(nextDayStart);
        verify(memberRepository).countByStatusAndWithdrawnAtLessThan(MemberStatus.WITHDRAWN, nextDayStart);
        verify(memberRepository).countByStatusAndSuspendedAtLessThan(MemberStatus.SUSPENDED, nextDayStart);
    }
}
