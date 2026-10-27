package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.contract.DeductionStatus;
import com.lunchcatch.billing.dto.ServeIds;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 묶음 차감이 판정과 원장을 노출마다가 아니라 묶음마다 한 문장으로 쓴다(#1049).
 * 배치 INSERT 는 rewriteBatchedStatements 로 여러 행 한 문장이 되어 행마다의 결과를 돌려주지 않는다.
 * 그래서 판정은 넣은 뒤 그 serve_id 들을 다시 읽어 created_at 이 이 호출의 시각인 행을 새로 넣은 것으로 본다.
 */
@Repository
@RequiredArgsConstructor
public class DeductionBatchRepository {

    // 단건 차감의 insertIfAbsent 와 달리 중복 분기가 아무것도 바꾸지 않는다. 새로 넣었는지는 created_at 으로 가른다
    private static final String INSERT_RESULT = """
            INSERT INTO deduction_result
                (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE serve_id = serve_id
            """;

    /*
     * 잠금 읽기(FOR SHARE)로 읽는다. 다른 트랜잭션이 먼저 넣고 커밋한 행은 일반 SELECT 의 스냅샷에 없을 수 있다.
     * 키 목록(:serveIds)은 NamedParameterJdbcTemplate 이 (?, ?, ...) 로 펼친다.
     */
    private static final String FIND_RESULTS = """
            SELECT serve_id, billing_status, created_at FROM deduction_result WHERE serve_id IN (:serveIds) FOR SHARE
            """;

    private static final String INSERT_DEDUCT = """
            INSERT INTO point_ledger
                (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
            VALUES (?, ?, ?, 'DEDUCT', 'REALTIME', ?, ?, ?)
            ON DUPLICATE KEY UPDATE serve_id = serve_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /*
     * 판정을 쓰고 노출마다 저장된 판정을 돌려준다. 이미 있던 노출은 처음 판정이고 inserted 가 false 다.
     * serve_id 는 서로 달라야 한다. 시각(now)은 마이크로초까지 잘라 넘긴다. 컬럼이 DATETIME(6) 이라 그래야 다시 읽은 값과 같다.
     */
    public Map<UUID, StoredResult> insertResults(List<ResultRow> rows, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        jdbcTemplate.batchUpdate(INSERT_RESULT, rows, rows.size(), (ps, row) -> {
            ps.setBytes(1, ServeIds.toBytes(row.serveId()));
            ps.setLong(2, row.campaignId());
            ps.setLong(3, row.ownerId());
            ps.setObject(4, row.businessDate());
            ps.setString(5, row.status().name());
            ps.setLong(6, row.unitPrice());
            ps.setTimestamp(7, at);
            ps.setTimestamp(8, at);
        });
        List<byte[]> serveIds = rows.stream().map(row -> ServeIds.toBytes(row.serveId())).toList();
        Map<UUID, StoredResult> stored = new HashMap<>();
        namedJdbcTemplate.query(FIND_RESULTS, new MapSqlParameterSource("serveIds", serveIds), rs -> {
            stored.put(ServeIds.fromBytes(rs.getBytes("serve_id")), new StoredResult(
                    DeductionStatus.valueOf(rs.getString("billing_status")),
                    rs.getTimestamp("created_at").toLocalDateTime().equals(now)));
        });
        return stored;
    }

    // 새로 넣은 BILLED 판정의 DEDUCT 를 쓴다. 금액은 단가의 음수다
    public void insertDeducts(List<DeductRow> rows, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        jdbcTemplate.batchUpdate(INSERT_DEDUCT, rows, rows.size(), (ps, row) -> {
            ps.setLong(1, row.ownerId());
            ps.setLong(2, row.campaignId());
            ps.setBytes(3, ServeIds.toBytes(row.serveId()));
            ps.setLong(4, -row.unitPrice());
            ps.setTimestamp(5, at);
            ps.setTimestamp(6, at);
        });
    }

    public record ResultRow(UUID serveId, Long campaignId, Long ownerId, LocalDate businessDate,
                            DeductionStatus status, long unitPrice) {
    }

    public record StoredResult(DeductionStatus status, boolean inserted) {
    }

    public record DeductRow(UUID serveId, Long campaignId, Long ownerId, long unitPrice) {
    }
}
