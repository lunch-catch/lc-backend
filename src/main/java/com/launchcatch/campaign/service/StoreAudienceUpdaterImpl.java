package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.StoreAudienceUpdater;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.StoreAudienceGender;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * store_audience를 유일 키(store_id, target_radius, gender, age_group)로 덮어쓴다. 한 SQL
 * 문에 여러 행을 INSERT ... VALUES (...), (...) ... ON DUPLICATE KEY UPDATE로 묶어서 왕복을
 * 줄인다. @Modifying @Query를 행마다 부르면 executeUpdate()가 행마다 따로 나가서
 * rewriteBatchedStatements=true를 켜도 왕복이 줄지 않는다. 그 플래그는 addBatch()로 모았다가
 * executeBatch() 한 번으로 보낼 때만 적용된다.
 *
 * MySQL max_allowed_packet 여유를 두려고 한 SQL 문에 최대 UPSERT_CHUNK_SIZE 행만 묶는다.
 * 더 끊어서 여러 번 부르는 건 광고 서빙의 몫이라(#178), 이 서비스는 한 번에 최대 몇 행까지만
 * 받는다.
 */
@Service
@RequiredArgsConstructor
public class StoreAudienceUpdaterImpl implements StoreAudienceUpdater {

    static final int MAX_CELLS_PER_CALL = 1000;
    private static final int UPSERT_CHUNK_SIZE = 250;
    private static final String VALUE_TUPLE = "(?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPSERT_SQL_PREFIX = """
            INSERT INTO store_audience
                (store_id, target_radius, gender, age_group, member_count, business_date, created_at, updated_at)
            VALUES
            """;

    /*
     * VALUES() 함수로 삽입될 값을 참조하는 옛 문법은 MySQL 8.0.20부터 지원 중단(deprecated)
     * 이라, 행 별칭(AS new_values)으로 참조하는 8.0.19 이상의 문법을 쓴다.
     */
    private static final String UPSERT_SQL_SUFFIX = """
            AS new_values
            ON DUPLICATE KEY UPDATE
                member_count = new_values.member_count,
                business_date = new_values.business_date,
                updated_at = new_values.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    @Transactional
    public void overwrite(List<StoreAudienceCell> cells) {
        Objects.requireNonNull(cells, "cells");
        if (cells.size() > MAX_CELLS_PER_CALL) {
            throw new IllegalArgumentException(
                    "cells must not exceed " + MAX_CELLS_PER_CALL + " per call: " + cells.size());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (int from = 0; from < cells.size(); from += UPSERT_CHUNK_SIZE) {
            int to = Math.min(from + UPSERT_CHUNK_SIZE, cells.size());
            upsertChunk(cells.subList(from, to), now);
        }
    }

    private void upsertChunk(List<StoreAudienceCell> chunk, LocalDateTime now) {
        String sql = UPSERT_SQL_PREFIX
                + chunk.stream().map(cell -> VALUE_TUPLE).collect(Collectors.joining(", "))
                + UPSERT_SQL_SUFFIX;
        Object[] params = chunk.stream()
                .flatMap(cell -> Stream.of(
                        cell.storeId(), cell.targetRadius(), toGenderColumn(cell.gender()).name(),
                        cell.ageGroup().name(), cell.memberCount(), cell.businessDate(), now, now))
                .toArray();
        jdbcTemplate.update(sql, params);
    }

    /*
     * TargetGender는 ALL을 허용하지만 store_audience.gender는 MALE/FEMALE/OTHER만 받는다.
     * OTHER는 #162(회원 쪽 성별 enum)가 먼저 정해져야 StoreAudienceCell이 받을 수 있어
     * 지금은 넘어올 수 없다. ALL이 들어오면 CHECK 제약 위반으로 터지기 전에 여기서 막는다.
     */
    private StoreAudienceGender toGenderColumn(TargetGender gender) {
        return switch (gender) {
            case MALE -> StoreAudienceGender.MALE;
            case FEMALE -> StoreAudienceGender.FEMALE;
            case ALL -> throw new IllegalArgumentException(
                    "ALL gender cannot be written to store_audience yet: #162 선행 필요");
        };
    }
}
