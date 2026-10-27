package com.lunchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.feed.repository.FeedVisitRepository;
import com.lunchcatch.adserving.feed.repository.ServeLogRepository;
import com.lunchcatch.adserving.feed.repository.SwipeLogRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LogRetentionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Mock FeedVisitRepository feedVisitRepository;
    @Mock SwipeLogRepository swipeLogRepository;
    @Mock ServeLogRepository serveLogRepository;
    @Mock ImpressionLogRepository impressionLogRepository;
    @InjectMocks LogRetentionService service;

    @Test
    void 테이블마다_보관_기간의_경계보다_앞선_영업일을_지운다() {
        service.purge(TODAY);

        // 접속 위치는 D-7 ~ D 의 8일, 스와이프는 D-30 ~ D 의 31일, 서빙과 노출은 13개월 전 같은 날부터 남는다
        verify(feedVisitRepository).deleteBusinessDateBefore(LocalDate.of(2026, 10, 13), 1000);
        verify(swipeLogRepository).deleteBusinessDateBefore(LocalDate.of(2026, 9, 20), 1000);
        verify(serveLogRepository).deleteBusinessDateBefore(LocalDate.of(2025, 9, 20), 1000);
        verify(impressionLogRepository).deleteBusinessDateBefore(LocalDate.of(2025, 9, 20), 1000);
    }

    @Test
    void 한_번에_천_행씩_지우고_천_행보다_적게_지워지면_멈추고_지운_합을_돌려준다() {
        when(impressionLogRepository.deleteBusinessDateBefore(any(), anyInt())).thenReturn(1000, 1000, 7);
        when(serveLogRepository.deleteBusinessDateBefore(any(), anyInt())).thenReturn(1000, 0);
        when(swipeLogRepository.deleteBusinessDateBefore(any(), anyInt())).thenReturn(3);

        int deleted = service.purge(TODAY);

        assertThat(deleted).isEqualTo(3010);
        verify(impressionLogRepository, times(3)).deleteBusinessDateBefore(any(), anyInt());
        verify(serveLogRepository, times(2)).deleteBusinessDateBefore(any(), anyInt());
        verify(swipeLogRepository, times(1)).deleteBusinessDateBefore(any(), anyInt());
        verify(feedVisitRepository, times(1)).deleteBusinessDateBefore(any(), anyInt());
    }

    @Test
    void 지우다_실패하면_예외를_올리고_뒤_테이블은_지우지_않는다() {
        when(feedVisitRepository.deleteBusinessDateBefore(any(), anyInt())).thenThrow(new IllegalStateException("db"));

        assertThatThrownBy(() -> service.purge(TODAY)).isInstanceOf(IllegalStateException.class);

        verify(impressionLogRepository, never()).deleteBusinessDateBefore(any(), anyInt());
    }
}
