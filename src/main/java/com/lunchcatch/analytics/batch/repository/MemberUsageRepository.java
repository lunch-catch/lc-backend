package com.lunchcatch.analytics.batch.repository;

import com.lunchcatch.analytics.batch.MemberUsage;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 회원 사용 통계(daily_member_usage)를 쓴다.
 *
 * 다시 집계했을 때 사라진 회원의 행이 남지 않도록 그날 행을 모두 지우고 넣는다. 부른 쪽이 연 트랜잭션 안에서
 * 도므로 읽는 쪽은 지운 상태를 보지 못한다. 이 표는 보존 삭제 대상이 아니다.
 */
@Repository
public class MemberUsageRepository {

    private static final String DELETE = "DELETE FROM daily_member_usage WHERE business_date = ?";

    private static final String INSERT = """
            INSERT INTO daily_member_usage (
                business_date, member_id, wish_count, redeem_count, saved_amount, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public MemberUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, List<MemberUsage.Row> rows, LocalDateTime now) {
        Date date = Date.valueOf(businessDate);
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(DELETE, date);
        jdbc.batchUpdate(INSERT, rows, rows.size(), (statement, row) -> {
            statement.setDate(1, date);
            statement.setLong(2, row.memberId());
            statement.setLong(3, row.wishCount());
            statement.setLong(4, row.redeemCount());
            statement.setLong(5, row.savedAmount());
            statement.setTimestamp(6, at);
            statement.setTimestamp(7, at);
        });
    }
}
