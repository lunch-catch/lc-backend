package com.launchcatch.adserving.impression.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

// 노출 상한을 판정하려고 그날 유효 노출 수를 캠페인마다 센다. idx_imp_campaign_date 를 탄다.
@Repository
@RequiredArgsConstructor
public class ImpressionLogQueryRepository {

    private static final String COUNT_VALID = """
            SELECT campaign_id, COUNT(*) AS valid_count
              FROM impression_log
             WHERE campaign_id IN (:campaignIds)
               AND business_date = :businessDate
               AND validity = 'VALID'
             GROUP BY campaign_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 유효 노출이 없는 캠페인은 결과에 없다.
    public Map<Long, Long> countValidByCampaign(LocalDate businessDate, Collection<Long> campaignIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (campaignIds.isEmpty()) {
            return counts;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("campaignIds", campaignIds)
                .addValue("businessDate", businessDate);
        jdbcTemplate.query(COUNT_VALID, params,
                rs -> {
                    counts.put(rs.getLong("campaign_id"), rs.getLong("valid_count"));
                });
        return counts;
    }
}
