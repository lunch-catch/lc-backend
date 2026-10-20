package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.adserving.impression.job.LogRetentionJob;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 보관 기간 삭제 작업(StandaloneJob.runCounting)을 입구로 MySQL 의 네 테이블에서 경계 앞의 영업일만 지워지는지 본다(#610).
 * 지우는 쿼리는 직접 쓴 SQL 이라 여기서만 실행된다. 서빙 로그는 1,000행을 넘게 넣어 나눠 지우는 반복도 실행한다.
 */
@DisplayName("광고 서빙 보관 기간 삭제 통합")
class LogRetentionIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate LOG_EDGE = LocalDate.of(2025, 9, 20);

    @Autowired LogRetentionJob job;

    @AfterEach
    void tearDown() {
        for (String table : List.of("feed_visit_daily", "swipe_log", "serve_log", "impression_log")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private void visit(LocalDate date, long memberId) {
        jdbcTemplate.update("INSERT INTO feed_visit_daily VALUES (?, ?, 37.498, 127.028, NOW(6), NOW(6))", date, memberId);
    }

    private void swipe(LocalDate date, long memberId) {
        jdbcTemplate.update("""
                INSERT INTO swipe_log (serve_id, member_id, business_date, campaign_id, category_code, action, occurred_at,
                                       created_at, updated_at)
                VALUES (UNHEX(MD5(CONCAT('swipe', ?))), ?, ?, 101, 'KOREAN', 'PASS', NOW(6), NOW(6), NOW(6))
                """, memberId, memberId, date);
    }

    private void impression(LocalDate date, long memberId) {
        jdbcTemplate.update("""
                INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, displayed_at, lat_rounded,
                                            lng_rounded, validity, created_at, updated_at)
                VALUES (UNHEX(MD5(CONCAT('imp', ?))), ?, ?, 101, NOW(6), 37.498, 127.028, 'VALID', NOW(6), NOW(6))
                """, memberId, memberId, date);
    }

    /*
     * 순번 from ~ to 의 서빙 로그를 그 영업일에 넣는다. serve_id 는 영업일과 순번으로 정한다.
     * 재귀 깊이 상한(cte_max_recursion_depth 1000) 때문에 한 번에 1,000행까지만 넣는다.
     */
    private void serves(LocalDate date, int from, int to) {
        jdbcTemplate.update("""
                INSERT INTO serve_log (serve_id, business_date, feed_id, member_id, campaign_id, store_id, slot_type,
                                       slot_position, served_at, created_at, updated_at)
                WITH RECURSIVE seq (i) AS (SELECT ? UNION ALL SELECT i + 1 FROM seq WHERE i < ?)
                SELECT UNHEX(MD5(CONCAT('serve', ?, '-', i))), ?, UNHEX(MD5('feed')), i, 101, 55, 'RELEVANCE', 1,
                       NOW(6), NOW(6), NOW(6)
                FROM seq
                """, from, to, date.toString(), date);
    }

    private long count(String table, LocalDate date) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE business_date = ?", Long.class, date);
    }

    @Test
    void 테이블마다_보관_기간이_지난_영업일만_지우고_지운_수를_돌려준다() {
        visit(TODAY.minusDays(8), 1L);
        visit(TODAY.minusDays(7), 2L);
        swipe(TODAY.minusDays(31), 1L);
        swipe(TODAY.minusDays(30), 2L);
        impression(LOG_EDGE.minusDays(1), 1L);
        impression(LOG_EDGE, 2L);
        serves(LOG_EDGE.minusDays(1), 1, 1000);
        serves(LOG_EDGE.minusDays(1), 1001, 1001);
        serves(LOG_EDGE, 1, 2);

        Integer deleted = job.runCounting(TODAY, 1);

        assertThat(deleted).isEqualTo(1 + 1 + 1001 + 1);
        assertThat(count("feed_visit_daily", TODAY.minusDays(8))).isZero();
        assertThat(count("feed_visit_daily", TODAY.minusDays(7))).isEqualTo(1);
        assertThat(count("swipe_log", TODAY.minusDays(31))).isZero();
        assertThat(count("swipe_log", TODAY.minusDays(30))).isEqualTo(1);
        assertThat(count("serve_log", LOG_EDGE.minusDays(1))).isZero();
        assertThat(count("serve_log", LOG_EDGE)).isEqualTo(2);
        assertThat(count("impression_log", LOG_EDGE.minusDays(1))).isZero();
        assertThat(count("impression_log", LOG_EDGE)).isEqualTo(1);
    }

    @Test
    void 같은_영업일로_다시_돌면_지울_행이_없어_결과가_같다() {
        visit(TODAY.minusDays(8), 1L);
        visit(TODAY.minusDays(7), 2L);
        job.runCounting(TODAY, 1);

        Integer deleted = job.runCounting(TODAY, 2);

        assertThat(deleted).isZero();
        assertThat(count("feed_visit_daily", TODAY.minusDays(7))).isEqualTo(1);
    }
}
