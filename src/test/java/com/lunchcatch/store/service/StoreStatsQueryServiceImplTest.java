package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lunchcatch.store.contract.FinalizedStoreCounts;
import com.lunchcatch.store.repository.StoreStatsQueryRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class StoreStatsQueryServiceImplTest {
    @Mock StoreStatsQueryRepository repository;
    @InjectMocks StoreStatsQueryServiceImpl service;

    @Test
    void 월말_영업일의_KST_자정_범위로_집계한다() {
        when(repository.finalizedStoreCounts(LocalDateTime.of(2026, 9, 30, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0))).thenReturn(new FinalizedStoreCounts(2, 5));

        assertThat(service.finalizedStoreCounts(LocalDate.of(2026, 9, 30)))
                .isEqualTo(new FinalizedStoreCounts(2, 5));
    }

    @Test
    void 영업일이_null이면_거부한다() {
        assertThatThrownBy(() -> service.finalizedStoreCounts(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void DB_장애를_0건으로_바꾸지_않는다() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.finalizedStoreCounts(LocalDateTime.of(2026, 10, 15, 0, 0),
                LocalDateTime.of(2026, 10, 16, 0, 0))).thenThrow(failure);

        assertThatThrownBy(() -> service.finalizedStoreCounts(LocalDate.of(2026, 10, 15))).isSameAs(failure);
    }
}
