package com.launchcatch.adserving.engine.service;

import static com.launchcatch.adserving.engine.AdCandidateFixture.BUSINESS_DATE;
import static com.launchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.CandidateChange;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.CandidateCampaignState;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.geo.BoundingBox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("그날 후보 메모리")
class CandidateCacheTest {

    private static final double LAT = 37.5563;
    private static final double LNG = 126.9220;
    private static final BoundingBox AROUND = BoundingBox.around(LAT, LNG, 5_000);

    private final AdCandidateRepository repository = mock(AdCandidateRepository.class);
    private final MovingClock clock = new MovingClock(LocalDateTime.of(2026, 10, 1, 12, 0).atZone(ClockConfig.ZONE).toInstant());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CandidateCache cache = new CandidateCache(repository, meterRegistry, clock);

    private final AdCandidate near = candidate(101, LAT, LNG).build();
    private final AdCandidate far = candidate(102, LAT + 1, LNG).build();
    private final AdCandidate soldOut = candidate(103, LAT, LNG).set("soldOutAt", LocalDateTime.of(2026, 10, 1, 11, 0)).build();
    private final AdCandidate paused = candidate(104, LAT, LNG).set("campaignState", CandidateCampaignState.PAUSED).build();

    @Nested
    @DisplayName("읽기")
    class Read {

        @Test
        void 상자_안의_서빙_가능하고_소진되지_않은_후보만_돌려준다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near, far, soldOut, paused));

            assertThat(cache.findServableWithin(BUSINESS_DATE, AROUND)).containsExactly(near);
        }

        @Test
        void 처음_읽을_때만_DB_에서_읽고_5초_안의_다음_읽기는_메모리에서_한다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near));

            cache.findServableWithin(BUSINESS_DATE, AROUND);
            clock.advance(Duration.ofSeconds(4));
            cache.findServableWithin(BUSINESS_DATE, AROUND);
            cache.isSoldOut(BUSINESS_DATE, 101L);

            verify(repository, times(1)).findByIdBusinessDate(BUSINESS_DATE);
        }

        // 알림을 놓쳐도 5초 안에 맞춰진다(#1042 결정 1)
        @Test
        void 오초가_지나면_통째로_다시_읽는다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near), List.of(near, soldOut));
            cache.findServableWithin(BUSINESS_DATE, AROUND);

            clock.advance(Duration.ofSeconds(5));

            assertThat(cache.isSoldOut(BUSINESS_DATE, 103L)).isTrue();
            verify(repository, times(2)).findByIdBusinessDate(BUSINESS_DATE);
        }

        @Test
        void 영업일이_바뀌면_그날_후보를_새로_읽는다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near));
            when(repository.findByIdBusinessDate(BUSINESS_DATE.plusDays(1))).thenReturn(List.of());
            cache.findServableWithin(BUSINESS_DATE, AROUND);

            assertThat(cache.findServableWithin(BUSINESS_DATE.plusDays(1), AROUND)).isEmpty();
        }

        @Test
        void 소진된_후보만_소진으로_보고_없는_후보는_소진이_아니다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near, soldOut));

            assertThat(cache.isSoldOut(BUSINESS_DATE, 103L)).isTrue();
            assertThat(cache.isSoldOut(BUSINESS_DATE, 101L)).isFalse();
            assertThat(cache.isSoldOut(BUSINESS_DATE, 999L)).isFalse();
        }

        // DB 가 잠깐 멈춰도 피드와 노출이 이전 후보로 이어 간다. 처음 읽기라 이전 후보가 없으면 예외를 올린다
        @Test
        void 다시_읽기가_실패하면_이전_후보를_쓰고_센다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE))
                    .thenReturn(List.of(near))
                    .thenThrow(new QueryTimeoutException("DB 가 늦다"));
            cache.findServableWithin(BUSINESS_DATE, AROUND);
            clock.advance(Duration.ofSeconds(6));

            assertThat(cache.findServableWithin(BUSINESS_DATE, AROUND)).containsExactly(near);
            assertThat(meterRegistry.counter(CandidateCache.RELOAD_FAILED_METRIC).count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("변경 알림")
    class Notification {

        @Test
        void 후보_하나의_알림이면_그_후보만_다시_읽어_바꾼다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near));
            cache.findServableWithin(BUSINESS_DATE, AROUND);
            AdCandidate nowSoldOut = candidate(101, LAT, LNG).set("soldOutAt", LocalDateTime.of(2026, 10, 1, 12, 0)).build();
            when(repository.findByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, 101L)).thenReturn(Optional.of(nowSoldOut));

            cache.refresh(BUSINESS_DATE, 101L);

            assertThat(cache.isSoldOut(BUSINESS_DATE, 101L)).isTrue();
            assertThat(cache.findServableWithin(BUSINESS_DATE, AROUND)).isEmpty();
            verify(repository, times(1)).findByIdBusinessDate(BUSINESS_DATE);
        }

        @Test
        void 그날_전체의_알림이면_다음_읽기에서_통째로_다시_읽는다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near), List.of(near, far));
            cache.findServableWithin(BUSINESS_DATE, AROUND);

            cache.expire(BUSINESS_DATE);
            cache.findServableWithin(BUSINESS_DATE, AROUND);

            verify(repository, times(2)).findByIdBusinessDate(BUSINESS_DATE);
        }

        // 다시 읽기가 알림 전의 값을 읽는 중이면, 알림은 그 다시 읽기가 끝난 뒤 걸려 다음 읽기가 다시 읽는다
        @Test
        void 다시_읽는_중에_온_그날_전체의_알림이_덮이지_않는다() throws Exception {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch committed = new CountDownLatch(1);
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near)).thenAnswer(invocation -> {
                reading.countDown();
                committed.await();
                return List.of(near);
            }).thenReturn(List.of(near, soldOut));
            cache.findServableWithin(BUSINESS_DATE, AROUND);
            clock.advance(Duration.ofSeconds(5));
            Thread reloader = new Thread(() -> cache.findServableWithin(BUSINESS_DATE, AROUND));
            reloader.start();
            reading.await();

            Thread notifier = new Thread(() -> cache.expire(BUSINESS_DATE));
            notifier.start();
            while (notifier.getState() != Thread.State.WAITING && notifier.isAlive()) {
                Thread.onSpinWait();
            }
            committed.countDown();
            reloader.join();
            notifier.join();

            assertThat(cache.isSoldOut(BUSINESS_DATE, 103L)).isTrue();
        }

        @Test
        void 받은_알림이_후보_하나면_그_후보를_그날_전체면_통째로_다시_읽는다() {
            when(repository.findByIdBusinessDate(BUSINESS_DATE)).thenReturn(List.of(near), List.of(near, soldOut));
            cache.findServableWithin(BUSINESS_DATE, AROUND);
            when(repository.findByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, 101L)).thenReturn(Optional.empty());

            cache.apply(CandidateChange.one(BUSINESS_DATE, 101L));
            assertThat(cache.findServableWithin(BUSINESS_DATE, AROUND)).isEmpty();

            cache.apply(CandidateChange.wholeDay(BUSINESS_DATE));
            assertThat(cache.isSoldOut(BUSINESS_DATE, 103L)).isTrue();
        }

        // 아직 읽지 않은 날의 알림은 할 일이 없다. 다음 읽기가 어차피 DB 에서 읽는다
        @Test
        void 메모리에_없는_날의_알림은_DB_를_읽지_않는다() {
            cache.refresh(BUSINESS_DATE, 101L);

            verify(repository, times(0)).findByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, 101L);
        }
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
