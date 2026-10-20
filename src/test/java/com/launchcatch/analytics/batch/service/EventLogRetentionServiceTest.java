package com.launchcatch.analytics.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.batch.repository.EventLogRetentionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventLogRetentionServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2025, 9, 19, 0, 0);

    @Mock EventLogRetentionRepository repository;

    private EventLogRetentionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T04:00:00+09:00"), SEOUL);
        service = new EventLogRetentionService(repository, clock);
    }

    @Test
    @DisplayName("삭제 선은 오늘에서 13개월 전 날짜의 00:00 이다")
    void 삭제_선은_13개월_전_자정이다() {
        assertThat(service.cutoff(LocalDate.of(2026, 10, 19))).isEqualTo(CUTOFF);
    }

    @Test
    @DisplayName("한 번에 다 지워지면 한 번만 지운다")
    void 한_번에_다_지워지면_한_번만_지운다() {
        when(repository.deleteOlderThan(CUTOFF, EventLogRetentionService.BATCH_SIZE)).thenReturn(7);

        int deleted = service.purge();

        assertThat(deleted).isEqualTo(7);
        verify(repository, times(1)).deleteOlderThan(any(), anyInt());
    }

    @Test
    @DisplayName("한 번에 가득 지웠으면 모자랄 때까지 반복해서 합계를 돌려준다")
    void 가득_지웠으면_끝까지_반복한다() {
        int batch = EventLogRetentionService.BATCH_SIZE;
        when(repository.deleteOlderThan(CUTOFF, batch)).thenReturn(batch, batch, 5);

        int deleted = service.purge();

        assertThat(deleted).isEqualTo(batch * 2 + 5);
        verify(repository, times(3)).deleteOlderThan(CUTOFF, batch);
    }

    @Test
    @DisplayName("지울 것이 없으면 0건으로 끝난다")
    void 지울_것이_없으면_0건이다() {
        when(repository.deleteOlderThan(CUTOFF, EventLogRetentionService.BATCH_SIZE)).thenReturn(0);

        assertThat(service.purge()).isZero();
    }

    @Test
    @DisplayName("쉬는 도중 중단 신호를 받으면 더 지우지 않고 예외를 던진다")
    void 중단_신호를_받으면_예외를_던진다() {
        int batch = EventLogRetentionService.BATCH_SIZE;
        when(repository.deleteOlderThan(CUTOFF, batch)).thenReturn(batch);
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> service.purge())
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
        } finally {
            // 중단 표시가 복원됐는지 보고, 다음 테스트로 새지 않게 지운다.
            assertThat(Thread.interrupted()).isTrue();
        }

        verify(repository, times(1)).deleteOlderThan(CUTOFF, batch);
    }
}
