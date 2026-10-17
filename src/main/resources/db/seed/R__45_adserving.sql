/*
 * 광고 서빙의 날짜에 묶이지 않는 행이다(#436).
 * 시험 회원은 로그인할 수 없어 HTTP 가 아니라 DB, 관리자, 분석 쪽에서 보인다.
 */

/*
 * 피드 필터다.
 * 회원 9021~9050 이 저장했다. 정렬은 번갈아 두고 업종은 회원마다 1~5개를 고른다.
 */
INSERT INTO feed_filter (member_id, sort_type, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 9021 UNION ALL SELECT n + 1 FROM seq WHERE n < 9050)
SELECT n, IF(MOD(n, 2) = 0, 'DISCOUNT_RATE', 'DISTANCE'), NOW(6), NOW(6)
FROM seq
ON DUPLICATE KEY UPDATE member_id = member_id;

INSERT INTO feed_filter_category (member_id, category_code, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 9021 UNION ALL SELECT n + 1 FROM seq WHERE n < 9050),
               pick (j) AS (SELECT 0 UNION ALL SELECT j + 1 FROM pick WHERE j < 4)
SELECT seq.n,
       ELT(MOD(seq.n + pick.j, 9) + 1, 'KOREAN', 'CHINESE', 'JAPANESE', 'WESTERN', 'BUNSIK', 'ASIAN', 'FAST_FOOD',
           'CAFE_DESSERT', 'OTHER'),
       NOW(6), NOW(6)
FROM seq
CROSS JOIN pick
WHERE pick.j <= MOD(seq.n, 5)
ON DUPLICATE KEY UPDATE feed_filter_category.member_id = feed_filter_category.member_id;

/*
 * 가게 상세에서 찜한 행이다. 출처 카드가 없다.
 * 회원 9011~9050 이 오늘 후보 9001~9012 가운데 셋씩 찜했다. 3의 배수 회원은 빈 찜 목록을 보려고 넣지 않는다.
 * 캠페인 c 의 가게는 c 와 같은 번호다.
 */
INSERT INTO wishlist (member_id, campaign_id, store_id, serve_id, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 9011 UNION ALL SELECT n + 1 FROM seq WHERE n < 9050),
               pick (k) AS (SELECT 0 UNION ALL SELECT k + 1 FROM pick WHERE k < 2)
SELECT seq.n, 9001 + MOD(seq.n + pick.k * 4, 12), 9001 + MOD(seq.n + pick.k * 4, 12), NULL,
       NOW(6) - INTERVAL (pick.k * 10 + MOD(seq.n, 7)) MINUTE, NOW(6) - INTERVAL (pick.k * 10 + MOD(seq.n, 7)) MINUTE
FROM seq
CROSS JOIN pick
WHERE MOD(seq.n, 3) <> 0
ON DUPLICATE KEY UPDATE wishlist.wishlist_id = wishlist.wishlist_id;
