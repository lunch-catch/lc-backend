package com.lunchcatch.adserving.impression.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository.PendingImpression;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

// 쿼리 자체는 ImpressionIntegrationTest 가 실제 MySQL 로 본다. 여기서는 DB 를 부르기 전의 분기와 돌려주는 값만 본다
class ImpressionDispatchRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);

    private final NamedParameterJdbcTemplate namedJdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final ImpressionDispatchRepository repository =
            new ImpressionDispatchRepository(mock(JdbcTemplate.class), namedJdbcTemplate);

    // 빈 키 목록은 IN () 이라 SQL 오류가 난다. 부르지 않고 0 을 돌려준다
    @Test
    void 넘긴_노출이_없으면_DB_를_부르지_않고_0_을_돌려준다() {
        assertThat(repository.markDispatched(List.of(), NOW)).isZero();

        verifyNoInteractions(namedJdbcTemplate);
    }

    // 다른 서버가 먼저 표시한 노출은 조건에서 빠져, 돌려주는 값이 넘긴 노출 수보다 작을 수 있다
    @Test
    void 실제로_표시한_행_수를_돌려준다() {
        when(namedJdbcTemplate.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        int marked = repository.markDispatched(List.of(pending(1), pending(2)), NOW);

        assertThat(marked).isEqualTo(1);
    }

    private static PendingImpression pending(int n) {
        return new PendingImpression(new UUID(0, n), 1_000L + n, 101L, LocalDate.of(2026, 11, 11), NOW.minusSeconds(3));
    }
}
