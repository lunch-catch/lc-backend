package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("회원 조회 캐시")
class MemberInfoCacheTest {

    private static final Long MEMBER_ID = 3001L;

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final MovingClock clock = new MovingClock(LocalDateTime.of(2026, 10, 1, 12, 0).atZone(ClockConfig.ZONE).toInstant());
    private final MemberInfoCache cache = new MemberInfoCache(memberQueryService, clock);

    private static MemberInfo member(boolean onboarded) {
        return new MemberInfo(MEMBER_ID, "점심러", MemberStatus.ACTIVE, onboarded, true, true,
                BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0), null, null);
    }

    @Test
    void 온보딩을_마친_회원은_30초_동안_다시_묻지_않는다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true)));

        cache.find(MEMBER_ID);
        clock.advance(Duration.ofSeconds(29));
        cache.find(MEMBER_ID);

        verify(memberQueryService, times(1)).findById(MEMBER_ID);
    }

    @Test
    void 삼십초가_지나면_다시_묻는다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true)));

        cache.find(MEMBER_ID);
        clock.advance(Duration.ofSeconds(30));
        cache.find(MEMBER_ID);

        verify(memberQueryService, times(2)).findById(MEMBER_ID);
    }

    // 캐시하면 온보딩을 마친 직후 최대 30초 동안 온보딩 오류가 난다(#1042 결정 3)
    @Test
    void 온보딩_미완료와_없는_회원은_캐시하지_않는다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(false)), Optional.empty(),
                Optional.of(member(true)));

        assertThat(cache.find(MEMBER_ID)).map(MemberInfo::onboardingCompleted).contains(false);
        assertThat(cache.find(MEMBER_ID)).isEmpty();
        assertThat(cache.find(MEMBER_ID)).map(MemberInfo::onboardingCompleted).contains(true);
        verify(memberQueryService, times(3)).findById(MEMBER_ID);
    }

    // 오래 쌓이지 않게, 항목이 문턱에 닿은 뒤 새로 넣을 때 만료된 항목을 치운다
    @Test
    void 항목이_정리_문턱에_닿으면_새로_넣을_때_만료된_항목을_치운다() {
        when(memberQueryService.findById(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
                Optional.of(new MemberInfo(invocation.getArgument(0), "점심러", MemberStatus.ACTIVE, true, true, true,
                        BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0), null, null)));
        for (long id = 1; id <= MemberInfoCache.SWEEP_THRESHOLD; id++) {
            cache.find(id);
        }
        clock.advance(Duration.ofSeconds(31));

        cache.find(MemberInfoCache.SWEEP_THRESHOLD + 1L);

        assertThat((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(cache, "entries"))
                .hasSize(1);
    }

    private static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ClockConfig.ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
