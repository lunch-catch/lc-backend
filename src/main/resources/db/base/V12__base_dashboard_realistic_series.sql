/*
 * 관리자 대시보드의 D-60 ~ D-1 일별 지표를 실제 서비스처럼 움직이게 다시 쓴다.
 * V7 은 매일 같은 공식이라 그래프가 평평했고, D-7 ~ D-1 은 원천 로그의 합이라 앞 구간과 수치가 20배쯤 어긋났다.
 * 이미 적용된 V7 은 체크섬 때문에 고치지 않는다. 그래서 그보다 뒤인 이 파일에서 같은 행을 고친다.
 *
 * 하루의 크기는 다섯 인자의 곱이다.
 *   증가 추세 g: D-60 의 0.5 에서 D-1 의 1.0 으로 오른다
 *   요일 wf: 점심 서비스라 평일이 1, 토요일 0.45, 일요일 0.38, 월요일 0.93, 금요일 1.07
 *   공휴일: 추석 연휴(9-24 ~ 9-26), 개천절과 대체공휴일(10-3, 10-5), 한글날(10-9)은 요일과 상관없이 0.36
 *   프로모션 sp: D-17 ~ D-15 사흘은 1.32
 *   잡음 n1 ~ n3: 날짜의 CRC32 로 만든 -0.5 ~ 0.5. 같은 날짜는 언제 돌려도 같은 값이다
 *
 * 퍼널은 캠페인 리포트의 합과 달라진다. 대시보드 그래프를 실제처럼 보이게 하는 것이 목적이라 받아들인다.
 * 회원과 가게의 누적은 V7 이 넣은 D-60 과 D-1 의 누적을 양 끝으로 두고 그 사이를 이 곡선으로 채운다. 신규는 누적의 차이에서 나온다.
 * 정산의 미소진 잔액은 V7 의 D-1 값을 끝으로 두고 거꾸로 센다. 충전, 차감, 환불, 조정이 맞물린다.
 * 사용자별, 캠페인별로 나뉜 무효 노출(daily_invalid_analytics)은 그대로 둔다.
 */

/*
 * 기준일은 자리표시자가 아니라 V7 이 넣은 행에서 읽는다.
 * 이미 기본 세계가 있는 DB 에서는 이 파일이 배포하는 날 돌아 baseDate 가 그날이 되기 때문이다.
 * V7 은 D-k 행을 D-k+1 의 01:03:00 정각에 만들고, 집계 작업이 쓴 행은 마이크로초가 붙어 섞이지 않는다.
 */
SET @d = COALESCE((SELECT DATE(MAX(created_at)) FROM daily_member_analytics WHERE created_at = TIMESTAMP(DATE(created_at), '01:03:00')),
                  DATE('${baseDate}'));

CREATE TEMPORARY TABLE v12_day (
    k INT PRIMARY KEY,
    d DATE NOT NULL,
    g DOUBLE NOT NULL,
    wf DOUBLE NOT NULL,
    sp DOUBLE NOT NULL,
    n1 DOUBLE NOT NULL,
    n2 DOUBLE NOT NULL,
    n3 DOUBLE NOT NULL,
    valid BIGINT NULL
);

INSERT INTO v12_day (k, d, g, wf, sp, n1, n2, n3)
WITH RECURSIVE r (k) AS (SELECT 1 UNION ALL SELECT k + 1 FROM r WHERE k < 60)
SELECT k,
       DATE_SUB(@d, INTERVAL k DAY),
       0.5 + 0.5 * POW((60 - k) / 59, 0.8),
       CASE
           WHEN DATE_SUB(@d, INTERVAL k DAY) IN ('2026-09-24', '2026-09-25', '2026-09-26', '2026-10-03', '2026-10-05', '2026-10-09') THEN 0.36
           WHEN DAYOFWEEK(DATE_SUB(@d, INTERVAL k DAY)) = 1 THEN 0.38
           WHEN DAYOFWEEK(DATE_SUB(@d, INTERVAL k DAY)) = 7 THEN 0.45
           WHEN DAYOFWEEK(DATE_SUB(@d, INTERVAL k DAY)) = 2 THEN 0.93
           WHEN DAYOFWEEK(DATE_SUB(@d, INTERVAL k DAY)) = 6 THEN 1.07
           ELSE 1
       END,
       IF(k BETWEEN 15 AND 17, 1.32, 1),
       (CRC32(CONCAT('v12a', DATE_SUB(@d, INTERVAL k DAY))) % 1000) / 1000 - 0.5,
       (CRC32(CONCAT('v12b', DATE_SUB(@d, INTERVAL k DAY))) % 1000) / 1000 - 0.5,
       (CRC32(CONCAT('v12c', DATE_SUB(@d, INTERVAL k DAY))) % 1000) / 1000 - 0.5
FROM r;

UPDATE v12_day SET valid = ROUND(16000 * g * wf * sp * (1 + 0.16 * n1));

-- 1. 퍼널. 유효 노출이 평일 최대 약 2만이고, 찜 약 8.5%, 발급 약 5.2%, 사용은 발급의 약 46%, 무효는 약 2.5% 다
UPDATE daily_funnel_analytics f
JOIN v12_day v ON v.d = f.business_date
SET f.valid_impression_count = v.valid,
    f.invalid_impression_count = ROUND(v.valid * (0.025 + 0.01 * v.n2)),
    f.wish_count = ROUND(v.valid * (0.085 + 0.01 * v.n3)),
    f.issue_count = ROUND(v.valid * (0.052 + 0.008 * v.n2)),
    f.redeem_count = ROUND(ROUND(v.valid * (0.052 + 0.008 * v.n2)) * (0.46 + 0.06 * v.n3));

/*
 * 2. 회원. 신규 가입은 출시 초반에 많고 주말에 줄며, 누적은 V7 의 양 끝을 잇는다.
 * 활성 회원은 누적의 약 30% 에 요일과 프로모션을 곱한다. 탈퇴와 정지 수는 V7 값을 둔다.
 */
SET @m_first = (SELECT cumulative_member_count FROM daily_member_analytics WHERE business_date = DATE_SUB(@d, INTERVAL 60 DAY));
SET @m_last = (SELECT cumulative_member_count FROM daily_member_analytics WHERE business_date = DATE_SUB(@d, INTERVAL 1 DAY));

CREATE TEMPORARY TABLE v12_member AS
SELECT k, d, wd, cum, LEAD(cum) OVER (ORDER BY k) AS cum_before
FROM (
    SELECT v.k, v.d, m.withdrawn_count AS wd,
           IF(v.k = 60, @m_first,
              ROUND(@m_first + (@m_last - @m_first)
                  * SUM(IF(v.k = 60, 0, (0.7 + 0.6 * v.k / 60) * IF(v.wf < 0.5, 0.7, 1) * (1 + 0.25 * v.n2)))
                        OVER (ORDER BY v.k DESC ROWS UNBOUNDED PRECEDING)
                  / SUM(IF(v.k = 60, 0, (0.7 + 0.6 * v.k / 60) * IF(v.wf < 0.5, 0.7, 1) * (1 + 0.25 * v.n2))) OVER ())) AS cum
    FROM v12_day v
    JOIN daily_member_analytics m ON m.business_date = v.d
) s;

UPDATE daily_member_analytics m
JOIN v12_member x ON x.d = m.business_date
JOIN v12_day v ON v.k = x.k
SET m.cumulative_member_count = x.cum,
    m.new_member_count = IF(x.k = 60, m.new_member_count, GREATEST(x.cum - x.cum_before + x.wd, 0)),
    m.active_member_count = LEAST(x.cum, ROUND(x.cum * 0.3 * v.wf * v.sp * (1 + 0.12 * v.n3)));

/*
 * 3. 가게. 입점은 평일에 몰리고 주말에는 거의 없다. 누적은 V7 의 양 끝을 잇는다.
 * 진행 중 캠페인 수는 누적 가게의 약 40 ~ 46% 다.
 */
SET @s_first = (SELECT cumulative_store_count FROM daily_store_analytics WHERE business_date = DATE_SUB(@d, INTERVAL 60 DAY));
SET @s_last = (SELECT cumulative_store_count FROM daily_store_analytics WHERE business_date = DATE_SUB(@d, INTERVAL 1 DAY));

CREATE TEMPORARY TABLE v12_store AS
SELECT k, d, cum, LEAD(cum) OVER (ORDER BY k) AS cum_before
FROM (
    SELECT v.k, v.d,
           IF(v.k = 60, @s_first,
              ROUND(@s_first + (@s_last - @s_first)
                  * SUM(IF(v.k = 60, 0, IF(v.wf < 0.5, 0.25, 1) * (1 + 0.4 * v.n1)))
                        OVER (ORDER BY v.k DESC ROWS UNBOUNDED PRECEDING)
                  / SUM(IF(v.k = 60, 0, IF(v.wf < 0.5, 0.25, 1) * (1 + 0.4 * v.n1))) OVER ())) AS cum
    FROM v12_day v
    JOIN daily_store_analytics s ON s.business_date = v.d
) s;

UPDATE daily_store_analytics s
JOIN v12_store x ON x.d = s.business_date
JOIN v12_day v ON v.k = x.k
SET s.cumulative_store_count = x.cum,
    s.new_store_count = IF(x.k = 60, s.new_store_count, GREATEST(x.cum - x.cum_before, 0)),
    s.active_campaign_count = ROUND(x.cum * (0.40 + 0.06 * v.g) * (1 + 0.04 * v.n2));

/*
 * 4. 정산. 차감은 그날 유효 노출의 97% 에 단가 10원이고, 충전은 차감의 0.7 ~ 1.7 배를 만 원 단위로 묶는다. 이따금 충전이 없는 날이 있다.
 * 예약은 V7 값과 차감 중 큰 쪽, 해제는 그 차이다. 환불과 조정은 V7 값을 둔다.
 * 미소진 잔액은 D-1 의 V7 값을 끝으로 두고, 앞날로 갈수록 그날 이후의 충전, 조정, 차감, 환불을 되돌려 센다.
 */
CREATE TEMPORARY TABLE v12_settle AS
SELECT v.k, v.d,
       ROUND(v.valid * 0.97) * 10 AS deduct,
       IF(v.n2 < -0.38, 0, ROUND(ROUND(v.valid * 0.97) * 10 * (1.2 + v.n1) * IF(v.wf < 0.5, 0.5, 1), -4)) AS charge,
       st.refund_amount AS refund, st.adjust_amount AS adjust
FROM v12_day v
JOIN daily_settlement_analytics st ON st.business_date = v.d;

SET @u_last = (SELECT unspent_balance FROM daily_settlement_analytics WHERE business_date = DATE_SUB(@d, INTERVAL 1 DAY));

CREATE TEMPORARY TABLE v12_balance AS
SELECT k, d, deduct, charge,
       @u_last - COALESCE(SUM(charge + adjust - deduct - refund) OVER (ORDER BY k ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS unspent
FROM v12_settle;

UPDATE daily_settlement_analytics st
JOIN v12_balance b ON b.d = st.business_date
SET st.deduct_amount = b.deduct,
    st.charge_amount = b.charge,
    st.reserved_amount = GREATEST(st.reserved_amount, b.deduct),
    st.released_amount = GREATEST(st.reserved_amount, b.deduct) - b.deduct,
    st.unspent_balance = b.unspent;

DROP TEMPORARY TABLE v12_balance;
DROP TEMPORARY TABLE v12_settle;
DROP TEMPORARY TABLE v12_store;
DROP TEMPORARY TABLE v12_member;
DROP TEMPORARY TABLE v12_day;
