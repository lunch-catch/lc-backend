/*
 * 템플릿과 포스터다 (#858). 받은 템플릿 디자인으로 바꿀 수 있게 기본 세계(V4)에서 떼어 냈다.
 * 내용이나 자리표시자 값이 바뀌면 다시 돈다. 다시 돌 때 기본 세계와 시연 캠페인의 포스터를 지우고 지금의 템플릿으로 다시 만든다.
 * 템플릿 B + 1 ~ B + 12 는 상태(게시 활성, 게시 비활성, 작성 중, 버전 25개)를 시나리오대로 두고 HTML 만 바꾼다. 실제 점주 포스터가 가리켜도 지우지 않는다.
 * 받은 디자인이 오기 전에는 임시 HTML 하나를 쓴다. 디자인을 받으면 tpl_html 의 내용만 바꾼다.
 * 캠페인 값은 campaign, store, store_menu 행을 읽어 만든다. 기본 세계(V4)와 시연 층(R__demo) 뒤에 돈다.
 */

-- 1. 지우기: 기본 세계(1,000만)와 시연(2,000만) 캠페인의 포스터와 검수 결과, 예전 시연 템플릿 "시연 기본형"
DELETE FROM poster_moderation_result
WHERE poster_id IN (SELECT poster_id FROM poster WHERE campaign_id BETWEEN 10000000 AND 29999999);
DELETE FROM poster WHERE campaign_id BETWEEN 10000000 AND 29999999;
DELETE FROM template_version
WHERE template_id = 20000001 AND NOT EXISTS (SELECT 1 FROM poster p WHERE p.template_id = 20000001);
DELETE FROM template
WHERE template_id = 20000001 AND NOT EXISTS (SELECT 1 FROM poster p WHERE p.template_id = 20000001);
DELETE FROM template_version WHERE template_id BETWEEN 10000001 AND 10000012;

/*
 * 2. 템플릿 HTML.
 * data-slot 칸 넷(eventName, discount, period, image)이 하나씩 있어야 앱이 값을 채운다.
 * 템플릿 t 마다 배경색만 다르다. 받은 디자인으로 바꿀 때는 t 마다 html 을 넣는다.
 */
CREATE TEMPORARY TABLE tpl_html (t INT PRIMARY KEY, html TEXT NOT NULL) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO tpl_html (t, html)
SELECT t.n,
       CONCAT('<div class="poster" style="background-color:',
              ELT(t.n % 8 + 1, '#FFF4E0', '#FFE3E3', '#E3F2FF', '#E8F7E8', '#F3E8FF', '#FFFBE0', '#E0FFF8', '#F0F0F0'),
              ';color:#222222;padding:16px;text-align:center"><h1 data-slot="eventName">이벤트명</h1>',
              '<p data-slot="discount">할인 내용</p><p data-slot="period">기간</p><img data-slot="image" alt="메뉴 이미지"></div>')
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
      UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12) t;

/*
 * 3. 템플릿 12개.
 * TPL.PUBLISHED_ACTIVE (1 ~ 8), TPL.PUBLISHED_INACTIVE (9, 10), TPL.DRAFT (11), TPL.DRAFT_20_VERSIONS, TPL.DRAFT_25_VERSIONS (12).
 * 이미 있으면 이름, 상태, HTML 을 다시 쓴다.
 */
INSERT INTO template (template_id, name, status, html_content, active, published_at, published_version_number,
                      activated_by, activated_at, last_modified_by, last_modified_at, created_at, updated_at)
SELECT 10000000 + h.t,
       CONCAT('임시 템플릿 ', h.t),
       CASE WHEN h.t <= 10 THEN 'PUBLISHED' ELSE 'DRAFT' END,
       CASE WHEN h.t <= 10 THEN h.html END,
       CASE WHEN h.t <= 8 THEN 1 ELSE 0 END,
       CASE WHEN h.t <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:00:00')
            WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00') END,
       CASE WHEN h.t <= 8 THEN 2 WHEN h.t <= 10 THEN 1 END,
       CASE WHEN h.t <= 8 THEN 10000001 END,
       CASE WHEN h.t <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:05:00') END,
       10000001,
       CASE WHEN h.t <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '12:00:00')
            WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00')
            WHEN h.t = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL 50 HOUR END,
       CASE WHEN h.t <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '10:00:00')
            WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00')
            WHEN h.t = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 3 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') END,
       CASE WHEN h.t <= 8 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:05:00')
            WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00')
            WHEN h.t = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL 50 HOUR END
FROM tpl_html h
ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status), html_content = VALUES(html_content),
                        active = VALUES(active), published_at = VALUES(published_at),
                        published_version_number = VALUES(published_version_number), activated_by = VALUES(activated_by),
                        activated_at = VALUES(activated_at), last_modified_by = VALUES(last_modified_by),
                        last_modified_at = VALUES(last_modified_at), updated_at = VALUES(updated_at);

/*
 * 4. 템플릿 버전.
 * 1 ~ 8 은 버전 2개(게시 때 soft delete), 9, 10 은 1개(게시 때 soft delete), 11 은 3개(D-3 ~ D-1), 12 는 25개(1 ~ 5 soft delete).
 * template_version_id = B + t * 100 + v 이다.
 */
INSERT INTO template_version (template_version_id, template_id, version_number, request_prompt, request_id,
                              html_content, message, created_at, updated_at, deleted_at)
SELECT 10000000 + v.t * 100 + v.v, 10000000 + v.t, v.v, '따뜻한 색감의 점심 할인 포스터', CONCAT('world-tpl-', v.t, '-', v.v),
       h.html, '요청하신 분위기로 템플릿을 만들었어요.', v.created_at, COALESCE(v.deleted_at, v.created_at), v.deleted_at
FROM (
    SELECT t.t, x.v,
           TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '10:00:00') + INTERVAL x.v HOUR AS created_at,
           TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:00:00') AS deleted_at
    FROM (SELECT 1 t UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
          UNION ALL SELECT 7 UNION ALL SELECT 8) t
    JOIN (SELECT 1 v UNION ALL SELECT 2) x
    UNION ALL
    SELECT t.t, 1, TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00'),
           TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00')
    FROM (SELECT 9 t UNION ALL SELECT 10) t
    UNION ALL
    SELECT 11, x.v, TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 4 - x.v DAY), '15:00:00'), NULL
    FROM (SELECT 1 v UNION ALL SELECT 2 UNION ALL SELECT 3) x
    UNION ALL
    SELECT 12, x.v, TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL x.v * 2 HOUR,
           CASE WHEN x.v <= 5 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 2 DAY), '04:00:00') END
    FROM (SELECT a.d * 5 + b.d + 1 v
          FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) a
          CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) b) x
) v
JOIN tpl_html h ON h.t = v.t;

-- template_quota 를 템플릿 행 수로 맞춘다
UPDATE template_quota
SET current_count = (SELECT COUNT(*) FROM template),
    updated_at = TIMESTAMP(DATE('${baseDate}'), '00:00:00');

/*
 * 5. 기본 세계 캠페인의 포스터 (PST.PASSED, PST.SAVED, PST.SAVED_FAILING).
 * DRAFT 222 ~ 241 은 단계 (n - 222) % 4 이고 단계 0 ~ 2 는 포스터가 없다. 그 밖의 캠페인은 모두 포스터가 있다.
 * 템플릿은 B + (n % 8) + 1 이고 n 은 campaign_id - B 다. 사진은 world/campaigns/<캠페인>/poster.jpg 다.
 * DRAFT 단계 3 중 241 은 검수 전, 나머지는 금지 표현 "최저가 보장" 이 들어가 검수 FAIL 이다.
 */
CREATE TEMPORARY TABLE tpl_poster AS
SELECT c.campaign_id AS id,
       c.campaign_id - 10000000 AS n,
       CASE WHEN c.status = 'DRAFT' THEN (c.campaign_id - 10000000 - 222) % 4 END AS stage,
       tp.template_id,
       tp.name AS template_name,
       tp.html_content AS template_html,
       st.name AS store_name,
       CONCAT(IF(c.discount_target_type = 'ALL', '전체 메뉴', m.name), ' ',
              IF(c.discount_type = 'PERCENT', CONCAT(c.discount_value, '%'), CONCAT(c.discount_value, '원')), ' 할인') AS discount_text,
       CONCAT('매일 ', TIME_FORMAT(c.usable_start_time, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end_time, '%H:%i')) AS period_text,
       CONCAT(TRIM(TRAILING '/' FROM '${baseCdnBaseUrl}'), '/world/campaigns/', c.campaign_id, '/poster.jpg') AS image_url,
       c.created_at + INTERVAL 30 MINUTE AS created_at
FROM campaign c
JOIN store st ON st.store_id = c.store_id
LEFT JOIN store_menu m ON m.store_menu_id = c.target_menu_id
JOIN template tp ON tp.template_id = 10000000 + (c.campaign_id - 10000000) % 8 + 1
WHERE c.campaign_id BETWEEN 10000001 AND 19999999
  AND (c.status <> 'DRAFT' OR (c.campaign_id - 10000000 - 222) % 4 = 3);

UPDATE tpl_poster SET discount_text = CONCAT(discount_text, ' 최저가 보장') WHERE stage = 3 AND n <> 241;

INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, created_at, updated_at,
                    image_object_key)
SELECT p.id, p.id, p.template_id, LEFT(CONCAT(p.store_name, ' - ', p.template_name), 100),
       JSON_OBJECT('eventName', JSON_OBJECT('type', 'text', 'value', p.store_name),
                   'discount', JSON_OBJECT('type', 'text', 'value', p.discount_text),
                   'period', JSON_OBJECT('type', 'text', 'value', p.period_text),
                   'image', JSON_OBJECT('type', 'image', 'value', p.image_url)),
       REPLACE(REPLACE(REPLACE(REPLACE(p.template_html,
           '>이벤트명<', CONCAT('>', p.store_name, '<')),
           '>할인 내용<', CONCAT('>', p.discount_text, '<')),
           '>기간<', CONCAT('>', p.period_text, '<')),
           'alt="메뉴 이미지">',
           CONCAT('alt="메뉴 이미지" src="', p.image_url, '" style="width:100%;height:100%;min-height:0;object-fit:cover">')),
       p.created_at, p.created_at, CONCAT('world/campaigns/', p.id, '/poster.jpg')
FROM tpl_poster p;

INSERT INTO poster_moderation_result (poster_moderation_result_id, poster_id, result, reason, created_at, updated_at)
SELECT p.id, p.id, IF(p.stage = 3, 'FAIL', 'PASS'), IF(p.stage = 3, 'FORBIDDEN_EXPRESSION', NULL),
       p.created_at + INTERVAL 10 MINUTE, p.created_at + INTERVAL 10 MINUTE
FROM tpl_poster p
WHERE p.stage IS NULL OR (p.stage = 3 AND p.n <> 241);

DROP TEMPORARY TABLE tpl_poster;

/*
 * 6. 시연 캠페인의 포스터 (시연 층 R__demo 의 캠페인, 2,000만 대역).
 * 시연 층만 다시 돌 때를 위해 R__demo 도 같은 방식으로 포스터를 만든다. 템플릿은 게시되고 활성인 첫 템플릿이다.
 */
INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, created_at, updated_at,
                    image_object_key)
SELECT c.campaign_id, c.campaign_id, tp.template_id, LEFT(CONCAT(st.name, ' - ', tp.name), 100),
       JSON_OBJECT('eventName', JSON_OBJECT('type', 'text', 'value', st.name),
                   'discount', JSON_OBJECT('type', 'text', 'value', CONCAT('전체 메뉴 ', FORMAT(c.discount_value, 0), '원 할인')),
                   'period', JSON_OBJECT('type', 'text', 'value',
                       CONCAT('매일 ', TIME_FORMAT(c.usable_start_time, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end_time, '%H:%i'))),
                   'image', JSON_OBJECT('type', 'image', 'value',
                       CONCAT(TRIM(TRAILING '/' FROM '${baseCdnBaseUrl}'), '/demo/campaigns/', c.campaign_id, '/poster.jpg'))),
       REPLACE(REPLACE(REPLACE(REPLACE(tp.html_content,
           '>이벤트명<', CONCAT('>', st.name, '<')),
           '>할인 내용<', CONCAT('>전체 메뉴 ', FORMAT(c.discount_value, 0), '원 할인<')),
           '>기간<', CONCAT('>매일 ', TIME_FORMAT(c.usable_start_time, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end_time, '%H:%i'), '<')),
           'alt="메뉴 이미지">',
           CONCAT('alt="메뉴 이미지" src="', TRIM(TRAILING '/' FROM '${baseCdnBaseUrl}'), '/demo/campaigns/', c.campaign_id,
                  '/poster.jpg" style="width:100%;height:100%;min-height:0;object-fit:cover">')),
       c.created_at + INTERVAL 30 MINUTE, c.created_at + INTERVAL 30 MINUTE,
       CONCAT('demo/campaigns/', c.campaign_id, '/poster.jpg')
FROM campaign c
JOIN store st ON st.store_id = c.store_id
JOIN (SELECT MIN(template_id) AS template_id FROM template WHERE status = 'PUBLISHED' AND active = 1) f
JOIN template tp ON tp.template_id = f.template_id
WHERE c.campaign_id BETWEEN 20000101 AND 20000999;

INSERT INTO poster_moderation_result (poster_moderation_result_id, poster_id, result, reason, created_at, updated_at)
SELECT p.poster_id, p.poster_id, 'PASS', NULL, p.created_at + INTERVAL 10 MINUTE, p.created_at + INTERVAL 10 MINUTE
FROM poster p
WHERE p.campaign_id BETWEEN 20000101 AND 20000999;

DROP TEMPORARY TABLE tpl_html;
