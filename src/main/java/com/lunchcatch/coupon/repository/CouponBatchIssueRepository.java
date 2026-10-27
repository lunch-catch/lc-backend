package com.lunchcatch.coupon.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/*
 * 묶음 발급이 여러 건을 SQL 한 문장씩으로 처리하는 저장소다.
 * 호출자는 READ_COMMITTED 트랜잭션 안에서 부르며 NamedParameterJdbcTemplate은 같은 커넥션과 트랜잭션에 참여한다.
 */
@Repository
@RequiredArgsConstructor
public class CouponBatchIssueRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /*
     * 회원들의 해당 영업일 카운터 행이 없으면 0으로 만들고 이미 있으면 그대로 둔다.
     * VALUES 목록 하나짜리 INSERT … ON DUPLICATE KEY UPDATE 한 문장으로 보낸다.
     * 작업자끼리 같은 순서로 잠그도록 호출자가 회원 ID를 오름차순으로 넘긴다.
     */
    public void ensureCounters(LocalDate businessDate, List<Long> sortedMemberIds, LocalDateTime now) {
        String sql = "INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)"
                + " VALUES :rows ON DUPLICATE KEY UPDATE member_id = member_id";
        List<Object[]> rows = new ArrayList<>();
        Timestamp at = Timestamp.valueOf(now);
        for (Long memberId : sortedMemberIds) {
            rows.add(new Object[]{memberId, businessDate, 0, at, at});
        }
        jdbcTemplate.update(sql, new MapSqlParameterSource("rows", rows));
    }

    /*
     * 회원들의 해당 영업일 카운터 행을 SELECT … FOR UPDATE로 잠그고 {회원 ID: 현재 발급 횟수}를 돌려준다.
     * 같은 회원의 다른 발급과 쿠폰 삭제의 차감이 이 트랜잭션이 끝날 때까지 기다린다.
     * 잠근 뒤에 읽으므로 READ_COMMITTED에서 앞 트랜잭션이 커밋한 최신 횟수를 본다.
     * 테이블이 작을 때 옵티마이저가 PK 순 전체 스캔을 고르면 작업자끼리 잠금 순서가 엇갈려 데드락이 난다.
     * 그래서 유니크 키를 고정해 항상 member_id 순으로 잠그게 한다.
     */
    public Map<Long, Integer> lockCounters(LocalDate businessDate, List<Long> sortedMemberIds) {
        String sql = "SELECT member_id, issued_count FROM coupon_daily_limit"
                + " FORCE INDEX (uk_coupon_daily_limit_member_date) WHERE business_date = :businessDate"
                + " AND member_id IN (:memberIds) ORDER BY member_id FOR UPDATE";
        MapSqlParameterSource args = new MapSqlParameterSource("businessDate", businessDate)
                .addValue("memberIds", sortedMemberIds);
        Map<Long, Integer> counts = new HashMap<>();
        jdbcTemplate.query(sql, args, rs -> {
            counts.put(rs.getLong(1), rs.getInt(2));
        });
        return counts;
    }

    /*
     * 주어진 이벤트와 회원 사이에 이미 발급한 (이벤트, 회원) 쌍을 잠그지 않고 읽는다.
     * 삭제한 쿠폰도 발급 기록으로 남으므로 상태와 관계없이 모두 센다.
     * 카운터 행을 먼저 잠근 뒤 부르므로 같은 회원의 앞 발급이 커밋한 쿠폰까지 보인다.
     */
    public Set<Pair> findIssuedPairs(Collection<Long> eventIds, Collection<Long> memberIds) {
        String sql = "SELECT coupon_event_id, member_id FROM member_coupon"
                + " WHERE coupon_event_id IN (:eventIds) AND member_id IN (:memberIds)";
        MapSqlParameterSource args = new MapSqlParameterSource("eventIds", eventIds)
                .addValue("memberIds", memberIds);
        Set<Pair> pairs = new HashSet<>();
        jdbcTemplate.query(sql, args, rs -> {
            pairs.add(new Pair(rs.getLong(1), rs.getLong(2)));
        });
        return pairs;
    }

    /*
     * 한 이벤트에서 Redis가 배정한 순번들의 재고를 한 문장으로 ISSUED로 바꾸고 바꾼 행 수를 돌려준다.
     * AVAILABLE인 행만 바꾸므로 이미 발급된 순번이 섞이면 돌려준 수가 넘긴 순번 수보다 작다.
     */
    public int issueSequences(long eventId, List<Integer> sequences, LocalDateTime now) {
        String sql = "UPDATE coupon_inventory SET status = 'ISSUED', updated_at = :now"
                + " WHERE coupon_event_id = :eventId AND status = 'AVAILABLE' AND sequence_no IN (:sequences)";
        MapSqlParameterSource args = new MapSqlParameterSource("now", Timestamp.valueOf(now))
                .addValue("eventId", eventId)
                .addValue("sequences", sequences);
        return jdbcTemplate.update(sql, args);
    }

    // 한 이벤트의 순번들에 붙은 쿠폰 코드를 {순번: 16바이트 코드}로 읽는다.
    public Map<Integer, byte[]> findCouponCodes(long eventId, List<Integer> sequences) {
        String sql = "SELECT sequence_no, coupon_code FROM coupon_inventory"
                + " WHERE coupon_event_id = :eventId AND sequence_no IN (:sequences)";
        MapSqlParameterSource args = new MapSqlParameterSource("eventId", eventId)
                .addValue("sequences", sequences);
        Map<Integer, byte[]> codes = new HashMap<>();
        jdbcTemplate.query(sql, args, rs -> {
            codes.put(rs.getInt(1), rs.getBytes(2));
        });
        return codes;
    }

    // 여러 이벤트의 정확한 (이벤트, 순번) 쌍을 한 SQL로 갱신한다.
    public int issueSequences(Map<Long, List<Integer>> sequencesByEvent, LocalDateTime now) {
        String sql = "UPDATE coupon_inventory SET status = 'ISSUED', updated_at = :now"
                + " WHERE status = 'AVAILABLE' AND (coupon_event_id, sequence_no) IN (:pairs)";
        return jdbcTemplate.update(sql, new MapSqlParameterSource("now", Timestamp.valueOf(now))
                .addValue("pairs", inventoryPairs(sequencesByEvent)));
    }

    // 서로 다른 이벤트의 코드도 정확한 쌍으로 한 번에 읽는다.
    public Map<Long, Map<Integer, byte[]>> findCouponCodes(Map<Long, List<Integer>> sequencesByEvent) {
        String sql = "SELECT coupon_event_id, sequence_no, coupon_code FROM coupon_inventory"
                + " WHERE (coupon_event_id, sequence_no) IN (:pairs)";
        Map<Long, Map<Integer, byte[]>> codes = new HashMap<>();
        jdbcTemplate.query(sql, new MapSqlParameterSource("pairs", inventoryPairs(sequencesByEvent)), rs -> {
            codes.computeIfAbsent(rs.getLong(1), key -> new HashMap<>()).put(rs.getInt(2), rs.getBytes(3));
        });
        return codes;
    }

    private List<Object[]> inventoryPairs(Map<Long, List<Integer>> sequencesByEvent) {
        List<Object[]> pairs = new ArrayList<>();
        sequencesByEvent.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                entry.getValue().stream().sorted().forEach(sequence ->
                        pairs.add(new Object[]{entry.getKey(), sequence})));
        return pairs;
    }

    /*
     * 회원마다 이번 묶음에서 발급한 수만큼 해당 영업일 카운터를 늘린다.
     * 대부분 한 장씩이라 같은 증가량을 받는 회원끼리 묶어 UPDATE … WHERE member_id IN 한 문장으로 보낸다.
     * 호출자는 lockCounters로 이 행들을 이미 잠근 상태에서 부른다.
     */
    public void increaseCounters(LocalDate businessDate, Map<Long, Integer> increments, LocalDateTime now) {
        Map<Integer, List<Long>> byIncrement = new HashMap<>();
        increments.forEach((memberId, increment) ->
                byIncrement.computeIfAbsent(increment, key -> new ArrayList<>()).add(memberId));
        byIncrement.forEach((increment, memberIds) -> {
            String sql = "UPDATE coupon_daily_limit SET issued_count = issued_count + :increment, updated_at = :now"
                    + " WHERE business_date = :businessDate AND member_id IN (:memberIds)";
            MapSqlParameterSource args = new MapSqlParameterSource("increment", increment)
                    .addValue("now", Timestamp.valueOf(now))
                    .addValue("businessDate", businessDate)
                    .addValue("memberIds", memberIds);
            jdbcTemplate.update(sql, args);
        });
    }

    /*
     * 회원 쿠폰을 다건 INSERT 한 문장으로 저장하고 생성된 ID를 넣은 순서대로 돌려준다.
     * 값 목록을 직접 준 INSERT라 MySQL이 연속한 ID를 한 번에 배정한다.
     */
    public List<Long> insertCoupons(List<NewCoupon> coupons) {
        String sql = "INSERT INTO member_coupon (coupon_event_id, member_id, coupon_code, qr_version, status,"
                + " usable_start_at, usable_end_at, created_at, updated_at) VALUES :rows";
        List<Object[]> rows = new ArrayList<>();
        for (NewCoupon coupon : coupons) {
            rows.add(new Object[]{coupon.eventId(), coupon.memberId(), coupon.couponCode(), 0, "ISSUED",
                    Timestamp.valueOf(coupon.usableStartAt()), Timestamp.valueOf(coupon.usableEndAt()),
                    Timestamp.valueOf(coupon.issuedAt()), Timestamp.valueOf(coupon.issuedAt())});
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, new MapSqlParameterSource("rows", rows), keys);
        return keys.getKeyList().stream()
                .map(row -> ((Number) row.values().iterator().next()).longValue())
                .toList();
    }

    // 중복 발급을 판정할 때 쓰는 (이벤트, 회원) 쌍이다.
    public record Pair(long eventId, long memberId) { }

    // 다건 INSERT로 저장할 회원 쿠폰 한 장의 값이다.
    public record NewCoupon(long eventId, long memberId, byte[] couponCode,
            LocalDateTime usableStartAt, LocalDateTime usableEndAt, LocalDateTime issuedAt) {

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof NewCoupon coupon
                    && eventId == coupon.eventId && memberId == coupon.memberId
                    && Arrays.equals(couponCode, coupon.couponCode)
                    && Objects.equals(usableStartAt, coupon.usableStartAt)
                    && Objects.equals(usableEndAt, coupon.usableEndAt)
                    && Objects.equals(issuedAt, coupon.issuedAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, memberId, Arrays.hashCode(couponCode), usableStartAt, usableEndAt, issuedAt);
        }

        @Override
        public String toString() {
            return "NewCoupon[eventId=" + eventId + ", memberId=" + memberId
                    + ", couponCode=" + Arrays.toString(couponCode) + ", usableStartAt=" + usableStartAt
                    + ", usableEndAt=" + usableEndAt + ", issuedAt=" + issuedAt + "]";
        }
    }
}
