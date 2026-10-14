package com.launchcatch.adserving.impression.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 노출 상한 판정에 쓰는 그날 유효 노출 수다. 광고 서빙이 자기 노출 로그로 센다(feed-api-design.md).
 * 노출을 받는 API 가 아직 없어 카운터를 올릴 자리가 없으므로 로그를 바로 센다(#236). idx_imp_campaign_date 를 탄다.
 */
@Repository
@RequiredArgsConstructor
public class ImpressionCountRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 유효 노출이 없는 캠페인은 결과에서 빠진다
    public Map<Long, Long> countValidByCampaign(Collection<Long> campaignIds, LocalDate businessDate) {
        Map<Long, Long> counts = new HashMap<>();
        if (campaignIds.isEmpty()) {
            return counts;
        }
        jdbcTemplate.query("""
                SELECT campaign_id, COUNT(*) AS valid_count FROM impression_log
                WHERE campaign_id IN (:campaignIds) AND business_date = :businessDate AND validity = 'VALID'
                GROUP BY campaign_id
                """, new MapSqlParameterSource()
                .addValue("campaignIds", campaignIds)
                .addValue("businessDate", businessDate), resultSet -> {
            counts.put(resultSet.getLong("campaign_id"), resultSet.getLong("valid_count"));
        });
        return counts;
    }
}
