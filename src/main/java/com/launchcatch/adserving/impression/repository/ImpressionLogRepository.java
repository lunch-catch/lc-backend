package com.launchcatch.adserving.impression.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ImpressionLogRepository {

    private static final String COUNT_VALID_SQL = """
            SELECT campaign_id, COUNT(*) AS valid_count
              FROM impression_log
             WHERE campaign_id IN (:campaignIds)
               AND business_date = :businessDate
               AND validity = 'VALID'
             GROUP BY campaign_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /*
     * 캠페인마다 그날 유효 노출 수를 센다. 노출 상한 판정에 쓴다(99행).
     * 유효 노출이 없는 캠페인은 결과에서 빠진다.
     */
    public Map<Long, Long> countValidByCampaign(LocalDate businessDate, Collection<Long> campaignIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (campaignIds.isEmpty()) {
            return counts;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("campaignIds", campaignIds)
                .addValue("businessDate", Date.valueOf(businessDate));
        jdbcTemplate.query(COUNT_VALID_SQL, params,
                rs -> {
                    counts.put(rs.getLong("campaign_id"), rs.getLong("valid_count"));
                });
        return counts;
    }
}
