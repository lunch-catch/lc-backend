package com.lunchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 릴레이는 묶음마다 밀린 양을 센다. 그 쿼리가 행 본문을 읽으면 밀린 노출이 많을수록 릴레이가 늦어진다(#1280).
 * 실제 MySQL 의 실행 계획으로 커버링 인덱스만 읽는지 본다.
 */
@DisplayName("노출 릴레이의 인덱스")
class ImpressionDispatchIndexIntegrationTest extends AbstractAdservingIntegrationTest {

    private String explainJson(String constant) {
        String sql = ((String) ReflectionTestUtils.getField(ImpressionDispatchRepository.class, constant))
                .replace(":from", "'2026-11-19 00:00:00'")
                .replace(":now", "'2026-11-20 12:00:00'")
                .replace("?", "'2026-11-19 00:00:00'");
        return jdbcTemplate.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class);
    }

    @Test
    void 밀린_양_세기는_idx_imp_dispatch_만_읽고_행_본문을_읽지_않는다() {
        String plan = explainJson("COUNT_PENDING");

        assertThat(plan).contains("\"key\": \"idx_imp_dispatch\"").contains("\"using_index\": true");
    }
}
