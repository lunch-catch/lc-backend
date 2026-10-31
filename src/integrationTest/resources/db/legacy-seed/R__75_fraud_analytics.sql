/*
 * 부정 관리 API(A-09, A-10)가 읽는 일별 집계다(#742).
 * 집계는 매일 01:00 배치가 impression_log 에서 채우므로 시드는 집계 테이블에 직접 넣는다.
 * 두 테이블은 서로 참조하지 않아 같은 캠페인의 무효 건수를 양쪽에 따로 넣고 합을 맞춘다.
 * 캠페인 9001~9012 와 회원 9001~9010 은 다른 시드가 만든 값이고 집계 테이블은 소프트 참조라 FK 가 없다.
 *
 * 날짜는 최초 적용일에서 2일 전부터 8일 전까지다. 최초 적용일은 PK 9001 행의 날짜에서 거꾸로 읽는다.
 * 날짜 시드처럼 날마다 새 행이 생기지 않고 재기동 뒤에도 최초 적용 때의 날짜와 수치가 그대로다.
 * 어제는 비워 둔다. 01:00 배치가 전날 하루를 지우고 다시 만들기 때문이다.
 *
 * 캠페인별 모양은 아래와 같다.
 * 9001 은 무효 비율 약 27% 로 비율 경고가 걸린다.
 * 9002 는 유효가 0 이고 무효 130 이라 비율 없이 경고가 걸린다.
 * 9003 은 비율이 99% 여도 무효가 99 로 최소 건수에 못 미쳐 경고가 없다.
 * 9004 는 정확히 20% 라 경고가 없다.
 * 9005 는 하루만 상위 회원 점유율이 0.92 라 집중 경고만 걸린다.
 * 9006 은 무효 비율 22% 이고 하루 점유율이 0.95 라 두 경고가 모두 걸린다.
 * 9007~9012 는 정상이라 목록에 나오지 않는다.
 */
SET @fraud_seed_day = COALESCE(
        (SELECT DATE_ADD(business_date, INTERVAL 2 DAY) FROM daily_invalid_analytics WHERE daily_invalid_analytics_id = 9001),
        DATE('${seedBusinessDate}'));

/*
 * A-09 의 원천이다. 회원, 캠페인, 사유, 날짜별 무효 건수다.
 * PK 는 날짜 순서로 매긴 번호에 9000 을 더한 값이다.
 * 9001 번은 가장 가까운 날(2일 전)의 첫 행이어야 위의 최초 적용일 읽기가 맞다.
 */
INSERT INTO daily_invalid_analytics (daily_invalid_analytics_id, business_date, reason_code, campaign_id, member_id,
                                     invalid_count, created_at, updated_at)
WITH RECURSIVE days (k) AS (SELECT 2 UNION ALL SELECT k + 1 FROM days WHERE k < 8),
               normal (c) AS (SELECT 9007 UNION ALL SELECT c + 1 FROM normal WHERE c < 9012),
               base (k, campaign_id, member_id, reason_code, cnt) AS (
                   SELECT days.k, 9001, m.member_id, m.reason_code, m.cnt
                   FROM days
                   CROSS JOIN (VALUES ROW(9001, 'EXPIRED_OR_UNKNOWN', 60), ROW(9002, 'EXPIRED_OR_UNKNOWN', 50),
                                      ROW(9003, 'NOT_OWNER', 30), ROW(9004, 'NOT_OWNER', 20)) AS m (member_id, reason_code, cnt)
                   UNION ALL SELECT 3, 9002, 9005, 'NOT_OWNER', 40
                   UNION ALL SELECT 3, 9002, 9006, 'EXPIRED_OR_UNKNOWN', 30
                   UNION ALL SELECT 4, 9002, 9005, 'NOT_OWNER', 35
                   UNION ALL SELECT 4, 9002, 9006, 'EXPIRED_OR_UNKNOWN', 25
                   UNION ALL SELECT 5, 9003, 9007, 'EXPIRED_OR_UNKNOWN', 60
                   UNION ALL SELECT 6, 9003, 9007, 'EXPIRED_OR_UNKNOWN', 39
                   UNION ALL SELECT 2, 9004, 9008, 'EXPIRED_OR_UNKNOWN', 60
                   UNION ALL SELECT 2, 9004, 9009, 'EXPIRED_OR_UNKNOWN', 40
                   UNION ALL SELECT days.k, 9005, 9010, 'EXPIRED_OR_UNKNOWN', 5 FROM days
                   UNION ALL SELECT 3, 9006, 9001, 'NOT_OWNER', 50
                   UNION ALL SELECT 3, 9006, 9002, 'NOT_OWNER', 40
                   UNION ALL SELECT 4, 9006, 9001, 'NOT_OWNER', 45
                   UNION ALL SELECT 4, 9006, 9003, 'EXPIRED_OR_UNKNOWN', 25
                   UNION ALL SELECT 5, 9006, 9001, 'NOT_OWNER', 35
                   UNION ALL SELECT 5, 9006, 9004, 'EXPIRED_OR_UNKNOWN', 25
                   UNION ALL SELECT days.k, normal.c, 9001 + MOD(normal.c + days.k, 10), 'EXPIRED_OR_UNKNOWN', 3
                   FROM days CROSS JOIN normal
               )
SELECT 9000 + ROW_NUMBER() OVER (ORDER BY k, campaign_id, member_id, reason_code),
       DATE_SUB(@fraud_seed_day, INTERVAL k DAY), reason_code, campaign_id, member_id, cnt, NOW(6), NOW(6)
FROM base
ON DUPLICATE KEY UPDATE daily_invalid_analytics.daily_invalid_analytics_id = daily_invalid_analytics.daily_invalid_analytics_id;

/*
 * A-10 의 원천이다. 캠페인과 영업일별 유효, 무효, 회원 집중이다.
 * 캠페인별 무효 합은 위 A-09 의 원천과 같다.
 * 집중 의심은 그날 상위 회원 점유율이 높은 날만 켠다. top_members_count 는 유효 노출을 넘지 못한다.
 */
INSERT INTO daily_campaign_fraud_analytics (business_date, campaign_id, valid_count, invalid_count,
                                            distinct_member_count, top_members_count, concentration_suspected,
                                            created_at, updated_at)
WITH RECURSIVE days (k) AS (SELECT 2 UNION ALL SELECT k + 1 FROM days WHERE k < 8),
               normal (c) AS (SELECT 9007 UNION ALL SELECT c + 1 FROM normal WHERE c < 9012),
               base (k, campaign_id, valid_count, invalid_count, distinct_member_count, top_members_count, suspected) AS (
                   SELECT days.k, 9001, 600, 160, 120, 40, 0 FROM days
                   UNION ALL SELECT 3, 9002, 0, 70, 0, 0, 0
                   UNION ALL SELECT 4, 9002, 0, 60, 0, 0, 0
                   UNION ALL SELECT 5, 9003, 50, 60, 20, 10, 0
                   UNION ALL SELECT 6, 9003, 50, 39, 20, 10, 0
                   UNION ALL SELECT 2, 9004, 500, 100, 200, 60, 0
                   UNION ALL SELECT days.k, 9005, IF(days.k = 4, 500, 400), 5, IF(days.k = 4, 4, 80),
                                    IF(days.k = 4, 460, 40), IF(days.k = 4, 1, 0) FROM days
                   UNION ALL SELECT 3, 9006, 300, 90, 60, 30, 0
                   UNION ALL SELECT 4, 9006, 400, 70, 3, 380, 1
                   UNION ALL SELECT 5, 9006, 300, 60, 60, 30, 0
                   UNION ALL SELECT days.k, normal.c, 300, 3, 100, 25, 0 FROM days CROSS JOIN normal
               )
SELECT DATE_SUB(@fraud_seed_day, INTERVAL k DAY), campaign_id, valid_count, invalid_count, distinct_member_count,
       top_members_count, suspected, NOW(6), NOW(6)
FROM base
ON DUPLICATE KEY UPDATE daily_campaign_fraud_analytics.campaign_id = daily_campaign_fraud_analytics.campaign_id;
