#!/usr/bin/env python3
# 시연 층 src/main/resources/db/base/R__demo.sql 을 만든다. 값 계산은 여기서 하고 날짜는 SQL 에서 기준일(@d)로부터 센다.
import sys

B = 20_000_000
OWNERS = {  # n: (email, store name, category, category_kr, lat, lng)
    11: ('demo-owner@lunchcatch.com', '런치캐치 시연 한식당', 'KOREAN', '한식', 37.4989, 127.0286),
    12: ('demo-owner-new@lunchcatch.com', '런치캐치 시연 분식', 'BUNSIK', '분식', 37.4969, 127.0266),
    13: ('demo-owner-scheduled@lunchcatch.com', '런치캐치 시연 스시', 'JAPANESE', '일식', 37.4999, 127.0296),
    14: ('demo-owner-soldout@lunchcatch.com', '런치캐치 시연 버거', 'FAST_FOOD', '패스트푸드', 37.4959, 127.0256),
    15: ('demo-owner-nopoints@lunchcatch.com', '런치캐치 시연 카페', 'CAFE_DESSERT', '카페디저트', 37.5009, 127.0306),
}
MENUS = {
    11: [('제육 정식', 10000), ('된장찌개 정식', 9000), ('비빔밥', 9500)],
    12: [('떡볶이 세트', 8000), ('참치 김밥', 4500), ('라볶이', 7000)],
    13: [('점심 초밥 12피스', 15000), ('연어 덮밥', 13000), ('우동', 9000)],
    14: [('치즈버거 세트', 9500), ('더블버거 세트', 11500), ('감자튀김', 3000)],
    15: [('아메리카노와 케이크', 9000), ('카페라테', 5000), ('크루아상', 4500)],
}
ALL_AGES = 'AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS'
DEMO_MEMBERS = 10  # B + 201 ~ 210

# 캠페인: id, 가게, 상태, 할인, 수량, 하루 예산, 시작(D 기준 일수), 끝, 사용 시간
CAMPAIGNS = [
    dict(id=101, s=11, status='ACTIVE', disc=3000, qty=50, budget=20000, start=-14, end=14),
    dict(id=111, s=11, status='ENDED', disc=2000, qty=50, budget=20000, start=-60, end=-54),
    dict(id=112, s=11, status='ENDED', disc=2500, qty=50, budget=20000, start=-45, end=-39),
    dict(id=113, s=11, status='ENDED', disc=3000, qty=50, budget=20000, start=-30, end=-24),
    dict(id=301, s=13, status='SCHEDULED', disc=3000, qty=50, budget=10000, start=0, end=14),
    dict(id=401, s=14, status='ACTIVE', disc=3000, qty=3, budget=20000, start=-7, end=7),
    dict(id=501, s=15, status='PAUSED', disc=3000, qty=50, budget=10000, start=-10, end=10),
]
USABLE = ('11:30:00', '15:00:00')
PAYMENTS = {11: [-80, -60, -40, -20, -5], 12: [-10], 13: [-10], 14: [-10], 15: [-11]}
PAY_AMOUNT = {15: 30000}


def d(k):
    if k == 0:
        return '@d'
    return f'DATE_ADD(@d, INTERVAL {k} DAY)' if k > 0 else f'DATE_SUB(@d, INTERVAL {-k} DAY)'


def ts(k, t):
    return f"TIMESTAMP({d(k)}, '{t}')"


def q(s):
    return "'" + s.replace("'", "''") + "'" if s is not None else 'NULL'


def spend(c, k):
    # 그날 소진액. 501 은 잔액이 정확히 0 이 되게 하루 3,000 이다
    if c['id'] == 501:
        return 3000
    if c['id'] == 401:
        return 600 + (abs(k) * 211) % 900 // 10 * 10
    return 1000 + (abs(k) * 337 + c['id'] * 53) % 4000 // 10 * 10


def spend_days(c):
    # 소진이 있는 지난 날 (D 이전, 기간 안)
    if c['status'] == 'SCHEDULED':
        return []
    last = min(c['end'], -1)
    return list(range(c['start'], last + 1))


def issued_on(c, k):
    if c['id'] == 101:
        return 2 + abs(k) % 4
    if c['id'] == 401:
        return 3
    return 0


out = []
w = out.append


def rows(table, cols, values, upsert=None):
    if not values:
        return
    w(f'INSERT INTO {table} ({", ".join(cols)})\nVALUES\n' + ',\n'.join('    (' + ', '.join(v) + ')' for v in values)
      + (f'\nON DUPLICATE KEY UPDATE {upsert}' if upsert else '') + ';\n')


w('''/*
 * 시연 층이다 (lc-backend #741, docs/scenarios/demo.md "시연 층에 넣는 것").
 * 기본 세계(V2 ~ V7) 뒤에 돌고, 내용이나 자리표시자 값이 바뀌면 다시 돈다.
 * 다시 돌 때 시연 점주와 시연 회원에 딸린 행을 모두 지우고 정해진 상태로 다시 넣는다. 리허설에서 생긴 행도 함께 지운다.
 * 계정 행(관리자, 점주)은 지우지 않는다. 이미 있으면 그대로 두어 사람이 바꾼 비밀번호를 되돌리지 않는다.
 * 번호는 2,000만 대역(B2 = 20000000)이다. 점주 B2 + 11 ~ 15, 가게는 점주와 같은 번호, 회원 B2 + 201 ~ 210 이다.
 * 이 파일은 scripts/gen_demo.py 가 만든다. 고칠 때는 생성기를 고치고 python3 scripts/gen_demo.py > src/main/resources/db/base/R__demo.sql 로 다시 만든다.
 */
SET @d = DATE('${baseDate}');

-- 1. 지우기

CREATE TEMPORARY TABLE demo_owner AS SELECT owner_id FROM owner WHERE owner_id BETWEEN 20000011 AND 20000015;
CREATE TEMPORARY TABLE demo_member AS SELECT member_id FROM member WHERE member_id BETWEEN 20000201 AND 20000210;
CREATE TEMPORARY TABLE demo_store AS SELECT store_id FROM store WHERE owner_id IN (SELECT owner_id FROM demo_owner);
CREATE TEMPORARY TABLE demo_campaign AS
SELECT campaign_id FROM campaign
WHERE owner_id IN (SELECT owner_id FROM demo_owner) OR store_id IN (SELECT store_id FROM demo_store);
CREATE TEMPORARY TABLE demo_event AS
SELECT coupon_event_id FROM coupon_event WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);
CREATE TEMPORARY TABLE demo_member_coupon AS
SELECT member_coupon_id FROM member_coupon
WHERE coupon_event_id IN (SELECT coupon_event_id FROM demo_event) OR member_id IN (SELECT member_id FROM demo_member);
CREATE TEMPORARY TABLE demo_serve AS
SELECT serve_id FROM serve_log
WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign) OR member_id IN (SELECT member_id FROM demo_member);
CREATE TEMPORARY TABLE demo_payment AS SELECT payment_id FROM payment WHERE owner_id IN (SELECT owner_id FROM demo_owner);
CREATE TEMPORARY TABLE demo_report AS
SELECT campaign_daily_report_id FROM campaign_daily_report WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);
CREATE TEMPORARY TABLE demo_plan AS
SELECT budget_daily_plan_id FROM budget_daily_plan WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);

DELETE FROM coupon_usage_history WHERE member_coupon_id IN (SELECT member_coupon_id FROM demo_member_coupon);
DELETE FROM member_coupon WHERE member_coupon_id IN (SELECT member_coupon_id FROM demo_member_coupon);
DELETE FROM coupon_inventory WHERE coupon_event_id IN (SELECT coupon_event_id FROM demo_event);
DELETE FROM coupon_event WHERE coupon_event_id IN (SELECT coupon_event_id FROM demo_event);
DELETE FROM coupon_daily_limit WHERE member_id IN (SELECT member_id FROM demo_member);

DELETE FROM deduction_result
WHERE serve_id IN (SELECT serve_id FROM demo_serve) OR campaign_id IN (SELECT campaign_id FROM demo_campaign);
DELETE FROM impression_log
WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign) OR member_id IN (SELECT member_id FROM demo_member);
DELETE FROM swipe_log
WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign) OR member_id IN (SELECT member_id FROM demo_member);
DELETE FROM wishlist
WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign) OR member_id IN (SELECT member_id FROM demo_member);
DELETE FROM serve_log WHERE serve_id IN (SELECT serve_id FROM demo_serve);
DELETE FROM ad_candidate WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);
DELETE FROM budget_hourly_target WHERE budget_daily_plan_id IN (SELECT budget_daily_plan_id FROM demo_plan);
DELETE FROM budget_daily_plan WHERE budget_daily_plan_id IN (SELECT budget_daily_plan_id FROM demo_plan);
DELETE FROM feed_filter_category WHERE member_id IN (SELECT member_id FROM demo_member);
DELETE FROM feed_filter WHERE member_id IN (SELECT member_id FROM demo_member);
DELETE FROM feed_visit_daily WHERE member_id IN (SELECT member_id FROM demo_member);

DELETE FROM settlement_mismatch
WHERE (check_type = 'PG_VS_CHARGE' AND target_id IN (SELECT payment_id FROM demo_payment))
   OR (check_type <> 'PG_VS_CHARGE' AND target_id IN (SELECT campaign_id FROM demo_campaign));
DELETE FROM point_ledger WHERE owner_id IN (SELECT owner_id FROM demo_owner);
DELETE FROM refund_request WHERE owner_id IN (SELECT owner_id FROM demo_owner);
DELETE FROM pg_charge_reconciliation_pending WHERE payment_id IN (SELECT payment_id FROM demo_payment);
DELETE FROM payment_reconciliation_outbox WHERE payment_id IN (SELECT payment_id FROM demo_payment);
DELETE FROM payment WHERE payment_id IN (SELECT payment_id FROM demo_payment);
DELETE FROM point_account WHERE owner_id IN (SELECT owner_id FROM demo_owner);

DELETE FROM campaign_daily_impression_reason_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM demo_report);
DELETE FROM campaign_daily_slot_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM demo_report);
DELETE FROM campaign_hourly_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM demo_report);
DELETE FROM campaign_daily_report_history WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM demo_report);
DELETE FROM campaign_daily_report WHERE campaign_daily_report_id IN (SELECT campaign_daily_report_id FROM demo_report);
DELETE FROM daily_campaign_fraud_analytics WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);
DELETE FROM daily_campaign_not_billed_analytics WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);
DELETE FROM daily_invalid_analytics
WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign) OR member_id IN (SELECT member_id FROM demo_member);
DELETE FROM daily_owner_spend WHERE owner_id IN (SELECT owner_id FROM demo_owner);
DELETE FROM daily_member_usage WHERE member_id IN (SELECT member_id FROM demo_member);
DELETE FROM event_log
WHERE member_id IN (SELECT member_id FROM demo_member)
   OR campaign_id IN (SELECT campaign_id FROM demo_campaign)
   OR store_id IN (SELECT store_id FROM demo_store);

DELETE FROM poster_moderation_result
WHERE poster_id IN (SELECT poster_id FROM poster WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign));
DELETE FROM poster WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);
DELETE FROM store_campaign_summary
WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign) OR store_id IN (SELECT store_id FROM demo_store);
DELETE FROM campaign_status_log WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);
DELETE FROM campaign WHERE campaign_id IN (SELECT campaign_id FROM demo_campaign);

DELETE FROM store_audience WHERE store_id IN (SELECT store_id FROM demo_store);
DELETE FROM store_menu_image WHERE store_menu_id IN (SELECT store_menu_id FROM store_menu WHERE store_id IN (SELECT store_id FROM demo_store));
DELETE FROM store_menu WHERE store_id IN (SELECT store_id FROM demo_store);
DELETE FROM store_image WHERE store_id IN (SELECT store_id FROM demo_store);
DELETE FROM store_business_hour WHERE store_id IN (SELECT store_id FROM demo_store);
DELETE FROM store_registration_progress WHERE owner_id IN (SELECT owner_id FROM demo_owner);
DELETE FROM store WHERE store_id IN (SELECT store_id FROM demo_store);

DELETE FROM kakao_unlink_failure WHERE member_id IN (SELECT member_id FROM demo_member);
DELETE FROM member_withdrawal_history WHERE member_id IN (SELECT member_id FROM demo_member);
DELETE FROM member_profile WHERE member_id IN (SELECT member_id FROM demo_member);
DELETE FROM member WHERE member_id IN (SELECT member_id FROM demo_member);

DROP TEMPORARY TABLE demo_plan;
DROP TEMPORARY TABLE demo_report;
DROP TEMPORARY TABLE demo_payment;
DROP TEMPORARY TABLE demo_serve;
DROP TEMPORARY TABLE demo_member_coupon;
DROP TEMPORARY TABLE demo_event;
DROP TEMPORARY TABLE demo_campaign;
DROP TEMPORARY TABLE demo_store;
DROP TEMPORARY TABLE demo_member;
DROP TEMPORARY TABLE demo_owner;

-- 2. 계정

/*
 * superadmin 은 같은 아이디가 있으면 넣지 않는다.
 * 개발과 운영의 기존 행(admin_id 3 등)이 그렇다.
 */
INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, deleted_at, created_at, updated_at)
SELECT 20000001, 'superadmin', '${superadminPasswordHash}', '최고관리자', 'SUPER_ADMIN', 'ACTIVE', NULL,
       TIMESTAMP(DATE_SUB(@d, INTERVAL 90 DAY), '09:00:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL 90 DAY), '09:00:00')
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM admin WHERE login_id = 'superadmin');

INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, deleted_at, created_at, updated_at)
SELECT 20000002, 'demo-admin', '${demoAdminPasswordHash}', '시연관리자', 'ADMIN', 'ACTIVE', NULL,
       TIMESTAMP(DATE_SUB(@d, INTERVAL 30 DAY), '09:00:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL 30 DAY), '09:00:00')
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM admin WHERE login_id = 'demo-admin');
''')

rows('owner', ['owner_id', 'email', 'password_hash', 'role', 'status', 'tutorial_viewed', 'suspended_at', 'suspension_reason',
               'withdrawn_at', 'last_login_at', 'created_at', 'updated_at'],
     [[str(B + n), q(v[0]), "'${demoOwnerPasswordHash}'", "'OWNER'", "'ACTIVE'", '1', 'NULL', 'NULL', 'NULL', 'NULL',
       ts(-90, '10:00:00'), ts(-90, '10:00:00')] for n, v in OWNERS.items()],
     'owner_id = owner_id')

# 시연 회원: 시연 쿠폰을 받고 쓴 사람이다. 기본 세계 회원의 하루 발급 한도를 건드리지 않으려고 따로 둔다
rows('member', ['member_id', 'provider_user_id', 'nickname', 'status', 'last_login_at', 'created_at', 'updated_at',
                'withdrawn_at', 'notification_opt_in', 'location_opt_in', 'location_opt_in_at', 'suspended_at',
                'suspended_until', 'suspension_reason'],
     [[str(B + 200 + m), q(f'demo-member-{m:02d}'), q(f'시연회원{m}'), "'ACTIVE'", ts(-1, '12:00:00'), ts(-70, '09:00:00'),
       ts(-70, '09:00:00'), 'NULL', '1', '1', ts(-70, '09:01:00'), 'NULL', 'NULL', 'NULL'] for m in range(1, DEMO_MEMBERS + 1)])
rows('member_profile', ['member_profile_id', 'member_id', 'gender', 'age_group', 'location_nickname', 'road_address',
                        'latitude', 'longitude', 'onboarding_completed_at', 'created_at', 'updated_at'],
     [[str(B + 200 + m), str(B + 200 + m), q(['MALE', 'FEMALE'][m % 2]),
       q(['AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS'][m % 4]), "'회사'", q(f'서울 강남구 시연로 {m}'),
       f'{37.4979 + m * 0.0002:.7f}', f'{127.0276 + m * 0.0002:.7f}', ts(-70, '09:05:00'), ts(-70, '09:05:00'),
       ts(-70, '09:05:00')] for m in range(1, DEMO_MEMBERS + 1)])

w('\n-- 3. 가게 (강남역 1 km 안, 확정, 로고와 매장 사진 3, 메뉴 3 과 사진, 월 ~ 토 브레이크 있음, 일요일 휴무)\n')
rows('store', ['store_id', 'owner_id', 'name', 'category_code', 'representative_name', 'phone', 'road_address', 'latitude',
               'longitude', 'kakao_place_id', 'business_registration_number', 'business_verification_status',
               'business_verified_at', 'finalized', 'finalized_at', 'created_at', 'updated_at'],
     [[str(B + n), str(B + n), q(v[1]), q(v[2]), q(f'시연대표{n}'), q(f'02-555-90{n}'), q(f'서울 강남구 강남대로 {390 + n}'),
       f'{v[4]:.7f}', f'{v[5]:.7f}', q(f'demo-{n}'), q(f'91000000{n}'), "'VERIFIED'", ts(-89, '11:00:00'), '1',
       ts(-89, '12:00:00'), ts(-89, '10:00:00'), ts(-89, '12:00:00')] for n, v in OWNERS.items()])
days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
hours = []
for n in OWNERS:
    for i, day in enumerate(days, 1):
        if day == 'SUNDAY':
            hours.append([str(B + n * 10 + i), str(B + n), q(day), 'NULL', 'NULL', '1', 'NULL', 'NULL', 'NULL',
                          ts(-89, '11:30:00'), ts(-89, '11:30:00')])
        else:
            hours.append([str(B + n * 10 + i), str(B + n), q(day), "'10:30'", "'21:30'", '0', "'15:00'", "'16:30'", "'21:00'",
                          ts(-89, '11:30:00'), ts(-89, '11:30:00')])
rows('store_business_hour', ['store_business_hour_id', 'store_id', 'day_of_week', 'open_time', 'close_time', 'closed',
                             'break_start_time', 'break_end_time', 'last_order_time', 'created_at', 'updated_at'], hours)
images = []
for n in OWNERS:
    for i, (kind, name) in enumerate([('LOGO', 'logo'), ('INTERIOR', 'interior-1'), ('INTERIOR', 'interior-2'),
                                      ('INTERIOR', 'interior-3')]):
        iid = B + n * 10 + i
        images.append([str(iid), str(B + n), q(f'0000000d-0000-4000-8000-{iid:012x}'), q(kind),
                       q(f'demo/stores/{B + n}/{name}.jpg'), q(f'{name}.jpg'), "'image/jpeg'", str(180000 + i * 1000),
                       '800', '800', 'NULL' if kind == 'LOGO' else str(i), "'VERIFIED'", ts(-89, '11:10:00'),
                       ts(-89, '11:05:00'), ts(-89, '11:10:00')])
rows('store_image', ['store_image_id', 'store_id', 'upload_id', 'image_type', 'object_key', 'original_filename',
                     'content_type', 'file_size', 'width', 'height', 'sort_order', 'upload_status', 'verified_at',
                     'created_at', 'updated_at'], images)
menus, menu_images = [], []
for n, ms in MENUS.items():
    for i, (name, price) in enumerate(ms, 1):
        mid = B + n * 10 + i
        menus.append([str(mid), str(B + n), q(name), str(price), q(f'시연 가게의 대표 메뉴 {i}'), str(i), ts(-89, '11:20:00'),
                      ts(-89, '11:20:00')])
        menu_images.append([str(mid), str(mid), q(f'0000000e-0000-4000-8000-{mid:012x}'), q(f'demo/stores/{B + n}/menu-{i}.jpg'),
                            q(f'menu-{i}.jpg'), "'image/jpeg'", str(150000 + i * 1000), "'VERIFIED'", ts(-89, '11:25:00'),
                            ts(-89, '11:20:00'), ts(-89, '11:25:00')])
rows('store_menu', ['store_menu_id', 'store_id', 'name', 'price', 'description', 'sort_order', 'created_at', 'updated_at'], menus)
rows('store_menu_image', ['store_menu_image_id', 'store_menu_id', 'upload_id', 'object_key', 'original_filename', 'content_type',
                          'file_size', 'upload_status', 'verified_at', 'created_at', 'updated_at'], menu_images)

w('''
/*
 * 4. 대비용 템플릿 "시연 기본형".
 * 0막에서 템플릿 생성이 늦거나 실패하면 쓴다.
 * HTML 은 기본 세계 템플릿 1 의 것을 쓰고, 기본 세계가 없으면 같은 모양의 기본 HTML 을 쓴다.
 */
INSERT INTO template (template_id, name, status, html_content, active, published_at, published_version_number,
                      activated_by, activated_at, last_modified_by, last_modified_at, created_at, updated_at)
SELECT 20000001, '시연 기본형', 'PUBLISHED',
       COALESCE((SELECT html_content FROM template WHERE template_id = 10000001),
                '<div class="poster" style="background-color:#F3E8FF;color:#000000"><h1 data-slot="eventName">이벤트명</h1><p data-slot="discount">할인 내용</p><p data-slot="period">기간</p><img data-slot="image" alt="메뉴 이미지"></div>'),
       1, TIMESTAMP(DATE_SUB(@d, INTERVAL 3 DAY), '10:00:00'), 1,
       20000002, TIMESTAMP(DATE_SUB(@d, INTERVAL 3 DAY), '10:05:00'), 20000002, TIMESTAMP(DATE_SUB(@d, INTERVAL 3 DAY), '09:50:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL 3 DAY), '09:30:00'), TIMESTAMP(DATE_SUB(@d, INTERVAL 3 DAY), '10:05:00')
FROM DUAL
ON DUPLICATE KEY UPDATE status = 'PUBLISHED', active = 1;

INSERT INTO template_version (template_version_id, template_id, version_number, request_prompt, request_id,
                              html_content, message, created_at, updated_at, deleted_at)
SELECT 20000001, 20000001, 1, '따뜻한 색감의 점심 할인 포스터', 'demo-tpl-1', t.html_content,
       '요청하신 분위기로 템플릿을 만들었어요.', TIMESTAMP(DATE_SUB(@d, INTERVAL 3 DAY), '09:40:00'),
       TIMESTAMP(DATE_SUB(@d, INTERVAL 3 DAY), '09:40:00'), NULL
FROM template t
WHERE t.template_id = 20000001
ON DUPLICATE KEY UPDATE template_version_id = template_version_id;

UPDATE template_quota SET current_count = (SELECT COUNT(*) FROM template);
''')

w('\n-- 5. 캠페인 (사용 시간은 모두 11:30 ~ 15:00, 대상 전 메뉴와 전 연령, 반경 1000)\n')
camp_rows, logs, posters, moderations = [], [], [], []
for c in CAMPAIGNS:
    cid = B + c['id']
    created = c['start'] - 3
    if c['status'] == 'ACTIVE':
        changed = ts(c['start'], '00:00:00')
    elif c['status'] == 'ENDED':
        changed = ts(c['end'] + 1, '00:00:00')
    elif c['status'] == 'SCHEDULED':
        changed = ts(-1, '15:00:00')
    else:
        changed = ts(0, '00:00:00')
    camp_rows.append([str(cid), str(B + c['s']), str(B + c['s']), q(c['status']), changed,
                      "'NO_POINTS'" if c['status'] == 'PAUSED' else 'NULL', "'ALL'", "'AMOUNT'", str(c['disc']), 'NULL',
                      str(c['qty']), q(USABLE[0]), q(USABLE[1]), 'NULL', 'NULL', '1000', "'ALL'", q(ALL_AGES),
                      str(c['budget']), 'NULL', d(c['start']), d(c['end']), ts(created, '10:00:00'), changed])
    lid = cid * 10 - B * 9  # B2 + id * 10 + j
    sched_at = ts(-1, '15:00:00') if c['status'] == 'SCHEDULED' else ts(c['start'] - 1, '15:00:00')
    seq = [('DRAFT', 'SCHEDULED', 'OWNER', None, sched_at)]
    if c['status'] in ('ACTIVE', 'ENDED', 'PAUSED'):
        seq.append(('SCHEDULED', 'ACTIVE', 'SYSTEM', None, ts(c['start'], '00:00:00')))
    if c['status'] == 'ENDED':
        seq.append(('ACTIVE', 'ENDED', 'SYSTEM', None, ts(c['end'] + 1, '00:00:00')))
    if c['status'] == 'PAUSED':
        seq.append(('ACTIVE', 'PAUSED', 'SYSTEM', 'cause=DAILY reservedAmount=0', ts(0, '00:00:00')))
    for j, (f, t, actor, reason, at) in enumerate(seq, 1):
        logs.append([str(B + c['id'] * 10 + j), str(cid), q(f), q(t), q(actor), q(reason), at, at])
    name = OWNERS[c['s']][1]
    disc_text = f"전체 메뉴 {c['disc']:,}원 할인"
    period = '매일 11:30 ~ 15:00'
    img = f"CONCAT(TRIM(TRAILING '/' FROM '${{baseCdnBaseUrl}}'), '/demo/campaigns/{cid}/poster.jpg')"
    slot = (f"JSON_OBJECT('eventName', JSON_OBJECT('type', 'text', 'value', {q(name)}), "
            f"'discount', JSON_OBJECT('type', 'text', 'value', {q(disc_text)}), "
            f"'period', JSON_OBJECT('type', 'text', 'value', {q(period)}), "
            f"'image', JSON_OBJECT('type', 'image', 'value', {img}))")
    html = (f"REPLACE(REPLACE(REPLACE(REPLACE(t.html_content, '>이벤트명<', {q('>' + name + '<')}), "
            f"'>할인 내용<', {q('>' + disc_text + '<')}), '>기간<', {q('>' + period + '<')}), "
            f"'alt=\"메뉴 이미지\">', CONCAT('alt=\"메뉴 이미지\" src=\"', {img}, "
            f"'\" style=\"width:100%;height:100%;min-height:0;object-fit:cover\">'))")
    posters.append((cid, name, slot, html, ts(created, '10:30:00')))
    moderations.append([str(cid), str(cid), "'PASS'", 'NULL', ts(created, '10:40:00'), ts(created, '10:40:00')])
rows('campaign', ['campaign_id', 'owner_id', 'store_id', 'status', 'status_changed_at', 'paused_reason', 'discount_target_type',
                  'discount_type', 'discount_value', 'target_menu_id', 'issue_quantity', 'usable_start_time', 'usable_end_time',
                  'min_order_amount', 'notice', 'target_radius', 'target_gender', 'target_age_groups', 'daily_budget',
                  'pending_daily_budget', 'start_date', 'end_date', 'created_at', 'updated_at'], camp_rows)
rows('campaign_status_log', ['campaign_status_log_id', 'campaign_id', 'from_status', 'to_status', 'actor_type', 'reason',
                             'created_at', 'updated_at'], logs)
w('\n/*\n * 포스터는 대비용 템플릿으로 만들고 검수를 통과했다.\n * 사진은 demo/campaigns/<캠페인>/poster.jpg 다.\n */\n')
for cid, name, slot, html, at in posters:
    w(f'''INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, created_at, updated_at,
                    image_object_key)
SELECT {cid}, {cid}, 20000001, {q(name + ' - 시연 기본형')}, {slot},
       {html},
       {at}, {at}, 'demo/campaigns/{cid}/poster.jpg'
FROM template t
WHERE t.template_id = 20000001;
''')
rows('poster_moderation_result', ['poster_moderation_result_id', 'poster_id', 'result', 'reason', 'created_at', 'updated_at'],
     moderations)

w('\n-- 6. 정산 (포인트 계정, 승인 결제와 충전, 날마다 예약과 해제, 오늘 예약)\n')
rows('point_account', ['point_account_id', 'owner_id', 'created_at', 'updated_at'],
     [[str(B + n), str(B + n), ts(-90, '10:00:00'), ts(-90, '10:00:00')] for n in OWNERS])
pays, charges = [], []
for n, ks in PAYMENTS.items():
    for j, k in enumerate(ks, 1):
        pid = B + n * 100 + j
        amount = PAY_AMOUNT.get(n, 100000)
        at = ts(k, '09:30:00')
        pays.append([str(pid), str(B + n), q(f'demo-order-{pid}'), q(f'demo_tgen_{pid}'), str(amount), "'APPROVED'", 'NULL',
                     'NULL', at, 'NULL', ts(k, '09:29:00'), at])
        charges.append([str(pid), str(B + n), 'NULL', 'NULL', str(pid), 'NULL', 'NULL', 'NULL', "'CHARGE'", "'REALTIME'",
                        str(amount), 'NULL', 'NULL', at, at])
rows('payment', ['payment_id', 'owner_id', 'order_id', 'pg_tid', 'amount', 'status', 'failure_reason', 'pg_cancel_id',
                 'approved_at', 'canceled_at', 'created_at', 'updated_at'], pays)
LEDGER_COLS = ['point_ledger_id', 'owner_id', 'campaign_id', 'serve_id', 'payment_id', 'refund_request_id', 'business_date',
               'adjust_target_date', 'entry_type', 'source', 'amount', 'reason', 'idempotency_key', 'created_at', 'updated_at']
plans, hourly, ledger = [], [], list(charges)
for c in CAMPAIGNS:
    cid = B + c['id']
    owner = B + c['s']
    plan_days = [k for k in range(c['start'], min(c['end'], 0) + 1)] if c['status'] != 'SCHEDULED' else []
    for k in plan_days:
        off = k + 100  # 0 ~ 114
        pid = B + 100000 + c['id'] * 1000 + off
        paused = c['status'] == 'PAUSED' and k == 0
        budget = 0 if paused else c['budget']
        if c['status'] == 'ENDED' and k > c['end']:
            continue
        plans.append([str(pid), str(cid), str(owner), d(k), str(budget), '10', '1' if paused else '0', ts(k, '00:00:00'),
                      ts(k, '00:00:00'), ts(0, '00:00:00') if paused else 'NULL'])
        if budget > 0:
            ledger.append([str(pid), str(owner), str(cid), 'NULL', 'NULL', 'NULL', d(k), 'NULL', "'RESERVE'", "'REALTIME'",
                           str(-budget), 'NULL', 'NULL', ts(k, '00:00:05'), ts(k, '00:00:05')])
        if k < 0 and budget > 0:
            ledger.append([str(pid + 500000), str(owner), str(cid), 'NULL', 'NULL', 'NULL', d(k), 'NULL', "'RELEASE'",
                           "'REALTIME'", str(budget - spend(c, k)), 'NULL', 'NULL', ts(k + 1, '00:00:14'), ts(k + 1, '00:00:14')])
        if k == 0 and budget > 0:
            per = budget // 24
            for h in range(24):
                hourly.append([str(B + 300000 + c['id'] * 100 + h), str(pid),
                               str(h), str(per + (budget - per * 24 if h == 23 else 0)), ts(0, '00:00:21'), ts(0, '00:00:21')])
rows('budget_daily_plan', ['budget_daily_plan_id', 'campaign_id', 'owner_id', 'business_date', 'daily_budget', 'unit_price',
                           'paused', 'created_at', 'updated_at', 'status_changed_at'], plans)
rows('budget_hourly_target', ['budget_hourly_target_id', 'budget_daily_plan_id', 'hour', 'target_amount', 'created_at',
                              'updated_at'], hourly)
rows('point_ledger', LEDGER_COLS, ledger)

w('\n/*\n * 7. 오늘 서빙과 쿠폰 (후보, 쿠폰 회차와 재고, 가게 캠페인 요약).\n * 지난 날 회차는 진행 중 캠페인의 발급과 사용 내역이다.\n */\n')
cands, events, inventory, mcs, usages, limits, summaries = [], [], [], [], [], [], []
limit_count = {}
for c in CAMPAIGNS:
    if c['status'] != 'ACTIVE':
        continue
    cid = B + c['id']
    sid = B + c['s']
    v = OWNERS[c['s']]
    logo = f"CONCAT(TRIM(TRAILING '/' FROM '${{baseCdnBaseUrl}}'), '/demo/stores/{sid}/logo.jpg')"
    cands.append(['@d', str(cid), str(sid), q(v[1]), logo, q(v[2]), f'{v[4]:.7f}', f'{v[5]:.7f}', '1000', "'ALL'", q(ALL_AGES),
                  "'ALL'", "'AMOUNT'", str(c['disc']), 'NULL', q(USABLE[0]), q(USABLE[1]), str(c['qty']),
                  ts(c['start'] - 3, '10:00:00'), 'NULL', "'SERVABLE'", ts(c['start'], '00:00:00'), 'NULL', ts(0, '00:00:26'),
                  ts(0, '00:00:26'), ts(0, '00:00:26')])
    for k in range(max(c['start'], -14), 1):
        eid = B + 400000 + c['id'] * 100 + (k + 50)
        n_issued = issued_on(c, k) if k < 0 else (2 if c['id'] == 401 else 0)
        events.append([str(eid), str(cid), d(k), str(c['qty']), ts(k, USABLE[0]), ts(k, USABLE[1]), "'ACTIVE'",
                       ts(c['start'], '00:00:00'), ts(k, '03:00:00'), ts(k, '03:00:00'), str(sid), q(v[1]), logo, "'ALL'",
                       "'AMOUNT'", str(c['disc']), 'NULL', 'NULL', 'NULL', 'NULL'])
        for seq in range(1, c['qty'] + 1):
            code = f"UNHEX('{(0xD0 << 120 | eid << 16 | seq):032x}')"
            issued = seq <= n_issued
            issue_at = ts(k, f'11:{seq:02d}:05') if issued else ts(k, '03:00:00')
            inventory.append([str(B + 5000000 + (eid - B - 400000) * 100 + seq),
                              str(eid), str(seq), code, "'ISSUED'" if issued else "'AVAILABLE'", ts(k, USABLE[0]),
                              ts(k, USABLE[1]), ts(k, '03:00:00'), issue_at])
            if not issued:
                continue
            m = (seq - 1) % DEMO_MEMBERS + 1
            mid = B + 200 + m
            mcid = B + 6000000 + (eid - B - 400000) * 100 + seq
            if k == 0:
                status = 'ISSUED'
            else:
                status = 'USED' if seq <= max(1, round(n_issued * 0.7)) else 'EXPIRED'
            used_at = ts(k, f'12:{seq:02d}:30')
            qr = f"UNHEX('{(0xD1 << 120 | eid << 16 | seq):032x}')" if status == 'USED' else 'NULL'
            mcs.append([str(mcid), str(eid), str(mid), code, '1' if status == 'USED' else '0', qr,
                        ts(k, f'12:{seq:02d}:50') if status == 'USED' else 'NULL', q(status), ts(k, USABLE[0]), ts(k, USABLE[1]),
                        issue_at, used_at if status == 'USED' else (ts(k, '15:05:00') if status == 'EXPIRED' else issue_at)])
            if status == 'USED':
                usages.append([str(mcid), str(mcid), str(sid), str(c['disc']), used_at, used_at])
            limit_count[(m, k)] = limit_count.get((m, k), 0) + 1
    for k in range(-7, 2):
        if c['start'] <= k <= c['end']:
            summaries.append([str(B + 700000 + c['id'] * 100 + k + 50), str(sid), str(cid), d(k), "'ACTIVE'", "'ALL'", "'AMOUNT'",
                              str(c['disc']), 'NULL', q(USABLE[0]), q(USABLE[1]), ts(k - 1, '23:50:00'), ts(k - 1, '23:50:00')])
for (m, k), cnt in sorted(limit_count.items()):
    limits.append([str(B + 800000 + (k + 50) * 100 + m), str(B + 200 + m), str(cnt), d(k), ts(k, '11:00:00'), ts(k, '11:00:00')])
rows('ad_candidate', ['business_date', 'campaign_id', 'store_id', 'store_name', 'store_image_url', 'category_code', 'latitude',
                      'longitude', 'target_radius', 'target_gender', 'target_age_groups', 'discount_target_type', 'discount_type',
                      'discount_value', 'discount_menu_name', 'usable_start_time', 'usable_end_time', 'issue_quantity',
                      'campaign_created_at', 'new_priority_until', 'campaign_state', 'state_changed_at', 'sold_out_at',
                      'snapshot_read_at', 'created_at', 'updated_at'], cands)
rows('coupon_event', ['coupon_event_id', 'campaign_id', 'business_date', 'issue_quantity', 'usable_start_at', 'usable_end_at',
                      'status', 'status_changed_at', 'created_at', 'updated_at', 'store_id', 'store_name', 'store_image_url',
                      'discount_target_type', 'discount_type', 'discount_value', 'target_menu_id', 'target_menu_name',
                      'target_menu_price', 'target_menu_discounted_price'], events)
for i in range(0, len(inventory), 500):
    rows('coupon_inventory', ['coupon_inventory_id', 'coupon_event_id', 'sequence_no', 'coupon_code', 'status', 'usable_start_at',
                              'usable_end_at', 'created_at', 'updated_at'], inventory[i:i + 500])
rows('member_coupon', ['member_coupon_id', 'coupon_event_id', 'member_id', 'coupon_code', 'qr_version', 'qr_token',
                       'qr_expires_at', 'status', 'usable_start_at', 'usable_end_at', 'created_at', 'updated_at'], mcs)
rows('coupon_usage_history', ['coupon_usage_history_id', 'member_coupon_id', 'store_id', 'discount_amount', 'created_at',
                              'updated_at'], usages)
rows('coupon_daily_limit', ['coupon_daily_limit_id', 'member_id', 'issued_count', 'business_date', 'created_at', 'updated_at'],
     limits)
rows('store_campaign_summary', ['store_campaign_summary_id', 'store_id', 'campaign_id', 'business_date', 'campaign_status',
                                'discount_target_type', 'discount_type', 'discount_value', 'target_menu_id', 'usable_start_time',
                                'usable_end_time', 'created_at', 'updated_at'], summaries)

w('\n/*\n * 8. 캠페인 일 리포트.\n * 지난 날은 FINALIZED, 오늘은 AGGREGATING 이다.\n * 소진은 원장의 예약 - 해제와 같다.\n */\n')
reports = []
for c in CAMPAIGNS:
    if c['status'] == 'SCHEDULED':
        continue
    cid = B + c['id']
    for k in range(c['start'], min(c['end'], 0) + 1):
        paused = c['status'] == 'PAUSED' and k == 0
        budget = 0 if paused else c['budget']
        sp = spend(c, k) if k < 0 else 0
        valid = sp // 10
        if c['status'] == 'ENDED':
            issued, redeem = 3 + abs(k) % 3, 2
        else:
            n_issued = issued_on(c, k) if k < 0 else 0
            issued = n_issued
            redeem = max(1, round(n_issued * 0.7)) if n_issued else 0
        rate = f'{sp / budget:.6f}' if budget else '0'
        reports.append([str(B + 900000 + c['id'] * 1000 + k + 100),
                        str(cid), str(B + c['s']), str(B + c['s']), d(k), str(budget), str(sp), '0', str(sp), rate, '10',
                        str(valid), str(valid), '0', '0', str(round(valid * 0.05)), str(issued), str(redeem),
                        "'AGGREGATING'" if k == 0 else "'FINALIZED'", 'NULL' if k == 0 else ts(k + 1, '01:05:00'),
                        ts(0, '00:10:00') if k == 0 else ts(k + 1, '01:03:00'),
                        ts(0, '00:10:00') if k == 0 else ts(k + 1, '01:05:00')])
rows('campaign_daily_report', ['campaign_daily_report_id', 'campaign_id', 'owner_id', 'store_id', 'business_date', 'daily_budget',
                               'charged_amount', 'refunded_amount', 'spent_amount', 'budget_spend_rate', 'impression_unit_cost',
                               'valid_impression_count', 'billable_impression_count', 'not_billed_impression_count',
                               'invalid_impression_count', 'wish_count', 'issued_count', 'redeem_count', 'report_status',
                               'finalized_at', 'created_at', 'updated_at'], reports)

w("""
/*
 * 9. 최근 7일 원천 (서빙, 노출, 과금, DEDUCT 원장).
 * 01:00 집계가 어제와 그제를 원천에서 다시 세므로, 원천이 없으면 위 리포트가 0 으로 덮인다.
 * 유효 노출 수는 그날 소진 / 10 이고 노출 단가는 10 이다. 회원은 시연 회원을 돌아가며 쓴다.
 */
CREATE TEMPORARY TABLE demo_n (n INT PRIMARY KEY);
INSERT INTO demo_n (n)
SELECT a.d * 100 + b.d * 10 + c.d + 1
FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c;

CREATE TEMPORARY TABLE demo_src (
    c INT NOT NULL,
    k INT NOT NULL,
    store_id BIGINT NOT NULL,
    cnt INT NOT NULL,
    PRIMARY KEY (c, k)
);
""")
src = []
for c in CAMPAIGNS:
    for k in spend_days(c):
        if -7 <= k <= -1:
            src.append([str(c['id']), str(k), str(B + c['s']), str(spend(c, k) // 10)])
rows('demo_src', ['c', 'k', 'store_id', 'cnt'], src)
w("""
CREATE TEMPORARY TABLE demo_serve AS
SELECT x.c, x.k, x.store_id, n.n AS i, DATE_ADD(@d, INTERVAL x.k DAY) AS bdate,
       TIMESTAMP(DATE_ADD(@d, INTERVAL x.k DAY), '11:30:00') + INTERVAL n.n * 7 SECOND AS served_at,
       UNHEX(CONCAT('0000000000d1', '7000', '8', LPAD(HEX(x.c * 1000000 + (x.k + 50) * 1000 + n.n), 15, '0'))) AS serve_id,
       20000200 + (n.n - 1) % 10 + 1 AS member_id,
       (n.n - 1) % 10 + 1 AS slot
  FROM demo_src x
  JOIN demo_n n ON n.n <= x.cnt;

INSERT INTO serve_log (serve_id, business_date, feed_id, member_id, campaign_id, store_id, slot_type, slot_position,
                       served_at, created_at, updated_at)
SELECT serve_id, bdate,
       UNHEX(CONCAT('0000000000d2', '7000', '8', LPAD(HEX((k + 50) * 100 + (member_id - 20000200)), 15, '0'))),
       member_id, 20000000 + c, store_id, IF(slot % 2 = 1, 'ALLOCATION', 'RELEVANCE'), slot, served_at, served_at, served_at
  FROM demo_serve;

INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type, slot_position,
                            displayed_at, lat_rounded, lng_rounded, validity, invalid_reason, sold_out, viewed_ms,
                            created_at, updated_at)
SELECT serve_id, member_id, bdate, 20000000 + c, store_id, IF(slot % 2 = 1, 'ALLOCATION', 'RELEVANCE'), slot,
       served_at + INTERVAL 2 SECOND, 37.498, 127.028, 'VALID', NULL, 0, 1500 + i * 13 % 4000,
       served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 SECOND
  FROM demo_serve;

INSERT INTO deduction_result (deduction_result_id, serve_id, campaign_id, owner_id, business_date, billing_status,
                              unit_price, created_at, updated_at)
SELECT 30000000 + c * 100000 + (k + 50) * 1000 + i, serve_id, 20000000 + c, store_id, bdate, 'BILLED', 10,
       served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 SECOND
  FROM demo_serve;

INSERT INTO point_ledger (point_ledger_id, owner_id, campaign_id, serve_id, payment_id, refund_request_id, business_date,
                          adjust_target_date, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
SELECT 30000000 + c * 100000 + (k + 50) * 1000 + i, store_id, 20000000 + c, serve_id, NULL, NULL, NULL, NULL,
       'DEDUCT', 'REALTIME', -10, NULL, NULL, served_at + INTERVAL 2 SECOND, served_at + INTERVAL 2 SECOND
  FROM demo_serve;

DROP TEMPORARY TABLE demo_serve;
DROP TEMPORARY TABLE demo_src;
DROP TEMPORARY TABLE demo_n;
""")

sys.stdout.write('\n'.join(out))
