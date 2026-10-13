package com.launchcatch.adserving.impression.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 노출 로그(impression_log)를 읽는다.
 * 쓰기는 POST /v1/impressions 의 몫이다
 */
@Repository
@RequiredArgsConstructor
public class ImpressionLogRepository {

    // idx_imp_campaign_date(campaign_id, business_date)가 받는다
    private static final String COUNT_VALID_SQL = """
            SELECT campaign_id, COUNT(*) AS valid_count
            FROM impression_log
            WHERE business_date = :businessDate AND campaign_id IN (:campaignIds) AND validity = 'VALID'
            GROUP BY campaign_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /*
     * 캠페인마다 그날 유효 노출 수를 센다.
     * 노출이 없는 캠페인은 결과에 없다
     */
    public Map<Long, Long> countValidByCampaign(LocalDate businessDate, Collection<Long> campaignIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (campaignIds.isEmpty()) {
            return counts;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessDate", businessDate)
                .addValue("campaignIds", campaignIds);
        jdbcTemplate.query(COUNT_VALID_SQL, params,
                (RowCallbackHandler) rs -> counts.put(rs.getLong("campaign_id"), rs.getLong("valid_count")));
        return counts;
    }
}
