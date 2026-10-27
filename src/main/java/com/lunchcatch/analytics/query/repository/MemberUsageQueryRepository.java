package com.lunchcatch.analytics.query.repository;

import com.lunchcatch.analytics.query.MemberUsageStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 회원 사용 통계 조회(A-12)가 읽는 일별 회원 사용 통계다. 조회는 이 자기 집계 테이블만 읽는다.
 * 날짜 범위를 두지 않는다. 이 표는 보존 삭제 대상이 아니라 가입 이후 전체 누적이다.
 */
@Repository
public class MemberUsageQueryRepository {

    private static final String SUM = """
            SELECT COALESCE(SUM(wish_count), 0) AS wish_count,
                   COALESCE(SUM(redeem_count), 0) AS redeem_count,
                   COALESCE(SUM(saved_amount), 0) AS saved_amount
            FROM daily_member_usage
            WHERE member_id = ?
            """;

    private final JdbcTemplate jdbc;

    public MemberUsageQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MemberUsageStats sumByMember(long memberId) {
        return jdbc.queryForObject(SUM,
                (rs, index) -> new MemberUsageStats(
                        rs.getLong("wish_count"), rs.getLong("redeem_count"), rs.getLong("saved_amount")),
                memberId);
    }
}
