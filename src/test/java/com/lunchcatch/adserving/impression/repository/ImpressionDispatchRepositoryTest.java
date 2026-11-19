package com.lunchcatch.adserving.impression.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository.PendingImpression;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

// 쿼리 자체는 ImpressionIntegrationTest 가 실제 MySQL 로 본다. 여기서는 DB 를 부르기 전의 분기와 돌려주는 값만 본다
class ImpressionDispatchRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);

    private final NamedParameterJdbcTemplate namedJdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final ImpressionDispatchRepository repository =
            new ImpressionDispatchRepository(mock(JdbcTemplate.class), namedJdbcTemplate);

    // 빈 키 목록은 IN () 이라 SQL 오류가 난다. 부르지 않고 빈 목록을 돌려준다
    @Test
    void 발행할_노출이_없으면_DB_를_부르지_않고_빈_목록을_돌려준다() {
        assertThat(repository.markDispatched(List.of(), NOW)).isEmpty();

        verifyNoInteractions(namedJdbcTemplate);
    }

    // 다른 서버가 먼저 표시한 노출은 잠가 읽은 행에서 빠져, 이 호출이 표시한 노출만 돌려준다
    @Test
    @SuppressWarnings("unchecked")
    void 아직_발행하지_않은_행만_표시하고_그_노출을_돌려준다() {
        PendingImpression mine = pending(1);
        PendingImpression other = pending(2);
        when(namedJdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> List.of(row(invocation.getArgument(2), mine)));

        List<PendingImpression> marked = repository.markDispatched(List.of(mine, other), NOW);

        assertThat(marked).containsExactly(mine);
        verify(namedJdbcTemplate).update(anyString(), any(SqlParameterSource.class));
    }

    // 잠가 읽은 행 하나를 저장소의 RowMapper 로 읽힌다
    private static Object row(RowMapper<?> mapper, PendingImpression impression) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBytes("serve_id")).thenReturn(ByteBuffer.allocate(16)
                .putLong(impression.serveId().getMostSignificantBits())
                .putLong(impression.serveId().getLeastSignificantBits()).array());
        when(rs.getLong("member_id")).thenReturn(impression.memberId());
        return mapper.mapRow(rs, 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 모두_다른_서버가_표시했으면_표시하지_않는다() {
        when(namedJdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());

        List<PendingImpression> marked = repository.markDispatched(List.of(pending(1)), NOW);

        assertThat(marked).isEmpty();
        verify(namedJdbcTemplate, never()).update(anyString(), any(SqlParameterSource.class));
    }

    private static PendingImpression pending(int n) {
        return new PendingImpression(new UUID(0, n), 1_000L + n, 101L, LocalDate.of(2026, 11, 11), NOW.minusSeconds(3));
    }
}
