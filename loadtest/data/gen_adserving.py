# 광고 서빙 부하 시험의 원천 데이터 SQL 을 만든다(#997). 그날 예약과 후보는 넣지 않고 00:00 묶음이 만든다.
#
#   python3 loadtest/data/gen_adserving.py --date 2026-10-27 > build/loadtest-adserving.sql
#
# 번호는 3,000만 대역(B = 30000000)이다. 다시 돌리면 그 대역의 행과 그날 배치 기록을 지우고 다시 넣는다.
# 부하 시험 DB 와 로컬에서만 쓴다. 공유 환경의 배치 기록을 지우므로 개발과 운영에 넣지 않는다.
import argparse
import random

B = 30_000_000
# 기본 세계의 가게가 모인 강남에서 10km 넘게 떨어진 홍대입구역이다. 피드가 시험 캠페인만 내려주게 한다
CENTER = (37.5563, 126.9220)
CATEGORIES = ['KOREAN', 'CHINESE', 'JAPANESE', 'WESTERN', 'BUNSIK', 'ASIAN', 'CAFE_DESSERT']
AGES = ['AGE_20S', 'AGE_30S', 'AGE_40S', 'AGE_50_PLUS']
ALL_AGES = ','.join(AGES)
DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
CHUNK = 1000

p = argparse.ArgumentParser()
p.add_argument('--date', required=True, help='측정하는 영업일(YYYY-MM-DD)')
p.add_argument('--members', type=int, default=5000)
p.add_argument('--stores', type=int, default=200)
p.add_argument('--daily-budget', type=int, default=1_000_000, help='캠페인 하루 예산(포인트). 시험 중 예약액에 닿지 않게 크게 둔다')
p.add_argument('--seed', type=int, default=997)
args = p.parse_args()
rnd = random.Random(args.seed)
out = []


def w(s):
    out.append(s)


def q(s):
    return 'NULL' if s is None else "'" + str(s).replace("'", "''") + "'"


def near(km):
    # 중심에서 km 안의 좌표다. 위도 1도는 약 111km, 경도 1도는 이 위도에서 약 88km 다
    return (CENTER[0] + rnd.uniform(-km, km) / 111, CENTER[1] + rnd.uniform(-km, km) / 88)


def rows(table, cols, values):
    for i in range(0, len(values), CHUNK):
        part = values[i:i + CHUNK]
        w(f'INSERT INTO {table} ({", ".join(cols)})\nVALUES\n'
          + ',\n'.join('    (' + ', '.join(v) + ')' for v in part) + ';\n')


owners = range(B + 1, B + args.stores + 1)
members = range(B + 1, B + args.members + 1)
at = "TIMESTAMP(DATE_SUB(@d, INTERVAL 30 DAY), '09:00:00')"

w(f'''-- 광고 서빙 부하 시험 원천 데이터(loadtest/data/gen_adserving.py 가 만든다)
SET @d = DATE('{args.date}');
SET @lo = {B + 1};
SET @hi = {B + 999_999};

-- 1. 지우기
DELETE FROM batch_execution_log WHERE business_date = @d;
DELETE FROM deduction_result WHERE campaign_id BETWEEN @lo AND @hi;
DELETE FROM impression_log WHERE member_id BETWEEN @lo AND @hi;
DELETE FROM swipe_log WHERE member_id BETWEEN @lo AND @hi;
DELETE FROM wishlist WHERE member_id BETWEEN @lo AND @hi;
DELETE FROM serve_log WHERE member_id BETWEEN @lo AND @hi;
DELETE FROM feed_visit_daily WHERE member_id BETWEEN @lo AND @hi;
DELETE FROM ad_candidate WHERE campaign_id BETWEEN @lo AND @hi;
DELETE FROM budget_daily_plan WHERE campaign_id BETWEEN @lo AND @hi;
DELETE FROM point_ledger WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM payment WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM point_account WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM store_campaign_summary WHERE store_id BETWEEN @lo AND @hi;
DELETE FROM campaign_status_log WHERE campaign_id BETWEEN @lo AND @hi;
DELETE FROM campaign WHERE campaign_id BETWEEN @lo AND @hi;
DELETE FROM store_audience WHERE store_id BETWEEN @lo AND @hi;
DELETE FROM store_menu WHERE store_id BETWEEN @lo AND @hi;
DELETE FROM store_image WHERE store_id BETWEEN @lo AND @hi;
DELETE FROM store_business_hour WHERE store_id BETWEEN @lo AND @hi;
DELETE FROM store WHERE store_id BETWEEN @lo AND @hi;
DELETE FROM owner WHERE owner_id BETWEEN @lo AND @hi;
DELETE FROM member_profile WHERE member_id BETWEEN @lo AND @hi;
DELETE FROM member WHERE member_id BETWEEN @lo AND @hi;

-- 2. 회원 {args.members}명(온보딩을 마쳤고 중심 1km 안에 산다)
''')

rows('member', ['member_id', 'provider_user_id', 'nickname', 'status', 'last_login_at', 'created_at', 'updated_at',
                'withdrawn_at', 'notification_opt_in', 'location_opt_in', 'location_opt_in_at', 'suspended_at',
                'suspended_until', 'suspension_reason'],
     [[str(m), q(f'loadtest-{m}'), q(f'부하{m - B}'), "'ACTIVE'", at, at, at, 'NULL', '1', '1', at, 'NULL', 'NULL', 'NULL']
      for m in members])
profiles = []
for m in members:
    lat, lng = near(1.0)
    profiles.append([str(m), str(m), q(['MALE', 'FEMALE'][m % 2]), q(AGES[m % 4]), "'회사'", q(f'서울 마포구 부하로 {m - B}'),
                     f'{lat:.7f}', f'{lng:.7f}', at, at, at])
rows('member_profile', ['member_profile_id', 'member_id', 'gender', 'age_group', 'location_nickname', 'road_address',
                        'latitude', 'longitude', 'onboarding_completed_at', 'created_at', 'updated_at'], profiles)

w(f'\n-- 3. 점주와 가게 {args.stores}개(중심 1.5km 안, 확정, 로고 하나, 메뉴 하나, 휴무 없음)\n')
rows('owner', ['owner_id', 'email', 'password_hash', 'role', 'status', 'tutorial_viewed', 'suspended_at', 'suspension_reason',
               'withdrawn_at', 'last_login_at', 'created_at', 'updated_at'],
     [[str(o), q(f'loadtest-{o}@lunchcatch.test'), "'loadtest'", "'OWNER'", "'ACTIVE'", '1', 'NULL', 'NULL', 'NULL', 'NULL',
       at, at] for o in owners])
stores = []
for o in owners:
    lat, lng = near(1.5)
    stores.append([str(o), str(o), q(f'부하가게{o - B}'), q(CATEGORIES[o % len(CATEGORIES)]), q(f'부하대표{o - B}'),
                   q(f'02-000-{(o - B) % 10000:04d}'), q(f'서울 마포구 부하대로 {o - B}'), f'{lat:.7f}', f'{lng:.7f}',
                   q(f'loadtest-{o}'), q(f'{(o - B) % 10**10:010d}'), "'VERIFIED'", at, '1', at, at, at])
rows('store', ['store_id', 'owner_id', 'name', 'category_code', 'representative_name', 'phone', 'road_address', 'latitude',
               'longitude', 'kakao_place_id', 'business_registration_number', 'business_verification_status',
               'business_verified_at', 'finalized', 'finalized_at', 'created_at', 'updated_at'], stores)
rows('store_business_hour', ['store_business_hour_id', 'store_id', 'day_of_week', 'open_time', 'close_time', 'closed',
                             'break_start_time', 'break_end_time', 'last_order_time', 'created_at', 'updated_at'],
     [[str(B + (o - B) * 10 + i), str(o), q(day), "'00:00'", "'23:59'", '0', 'NULL', 'NULL', 'NULL', at, at]
      for o in owners for i, day in enumerate(DAYS, 1)])
rows('store_image', ['store_image_id', 'store_id', 'upload_id', 'image_type', 'object_key', 'original_filename',
                     'content_type', 'file_size', 'width', 'height', 'sort_order', 'upload_status', 'verified_at',
                     'created_at', 'updated_at'],
     [[str(o), str(o), q(f'0000000f-0000-4000-8000-{o:012x}'), "'LOGO'", q(f'loadtest/stores/{o}/logo.jpg'), "'logo.jpg'",
       "'image/jpeg'", '180000', '800', '800', 'NULL', "'VERIFIED'", at, at, at] for o in owners])
rows('store_menu', ['store_menu_id', 'store_id', 'name', 'price', 'description', 'sort_order', 'created_at', 'updated_at'],
     [[str(o), str(o), q(f'부하메뉴{o - B}'), '9000', "'부하 시험 메뉴'", '1', at, at] for o in owners])

w('\n-- 4. 캠페인(가게마다 하나, 어제 시작해 진행 중, 전 연령과 성별, 반경 3000, 수량은 시험 중 소진되지 않게 크게)\n')
start = "DATE_SUB(@d, INTERVAL 1 DAY)"
changed = f"TIMESTAMP({start}, '00:00:00')"
rows('campaign', ['campaign_id', 'owner_id', 'store_id', 'status', 'status_changed_at', 'paused_reason', 'discount_target_type',
                  'discount_type', 'discount_value', 'target_menu_id', 'issue_quantity', 'usable_start_time', 'usable_end_time',
                  'min_order_amount', 'notice', 'target_radius', 'target_gender', 'target_age_groups', 'daily_budget',
                  'pending_daily_budget', 'start_date', 'end_date', 'created_at', 'updated_at'],
     [[str(o), str(o), str(o), "'ACTIVE'", changed, 'NULL', "'ALL'", "'AMOUNT'", str(1000 + (o % 5) * 500), 'NULL', '100000',
       "'11:30:00'", "'15:00:00'", 'NULL', 'NULL', '3000', "'ALL'", q(ALL_AGES), str(args.daily_budget), 'NULL', start,
       'DATE_ADD(@d, INTERVAL 30 DAY)', at, changed] for o in owners])
rows('campaign_status_log', ['campaign_status_log_id', 'campaign_id', 'from_status', 'to_status', 'actor_type', 'reason',
                             'created_at', 'updated_at'],
     [[str(o), str(o), "'SCHEDULED'", "'ACTIVE'", "'SYSTEM'", 'NULL', changed, changed] for o in owners])

w('\n-- 5. 정산(포인트 계정과 승인된 충전 하나. 00:00 묶음이 그날 하루 예산을 예약한다)\n')
charge = args.daily_budget * 40
rows('point_account', ['point_account_id', 'owner_id', 'created_at', 'updated_at'], [[str(o), str(o), at, at] for o in owners])
rows('payment', ['payment_id', 'owner_id', 'order_id', 'pg_tid', 'amount', 'status', 'failure_reason', 'pg_cancel_id',
                 'approved_at', 'canceled_at', 'created_at', 'updated_at'],
     [[str(o), str(o), q(f'loadtest-order-{o}'), q(f'loadtest_tgen_{o}'), str(charge), "'APPROVED'", 'NULL', 'NULL', at, 'NULL',
       at, at] for o in owners])
rows('point_ledger', ['point_ledger_id', 'owner_id', 'campaign_id', 'serve_id', 'payment_id', 'refund_request_id', 'business_date',
                      'adjust_target_date', 'entry_type', 'source', 'amount', 'reason', 'idempotency_key', 'created_at',
                      'updated_at'],
     [[str(o), str(o), 'NULL', 'NULL', str(o), 'NULL', 'NULL', 'NULL', "'CHARGE'", "'REALTIME'",
       str(charge), 'NULL', 'NULL', at, at] for o in owners])

w(f'''
-- 6. 재실행 API 를 부를 관리자(B + 1). 감사 로그가 이 관리자를 남긴다
INSERT INTO admin (admin_id, login_id, password_hash, name, role, status, deleted_at, created_at, updated_at)
SELECT {B + 1}, 'loadtest-admin', 'loadtest', '부하관리자', 'SUPER_ADMIN', 'ACTIVE', NULL, {at}, {at}
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM admin WHERE admin_id = {B + 1});
''')

w('''
/*
 * 7. 그날 00:00 묶음을 실패로 둔다.
 * run.sh 가 관리자 재실행 API 로 이 묶음을 다시 돌려 예약, 활성화, 그날 후보 적재를 만든다.
 */
INSERT INTO batch_execution_log (job_name, business_date, status, finished_at, failure_reason, processed_count, owner_id,
                                 created_at, updated_at)
VALUES ('DAILY_0000', @d, 'FAILED', NOW(6), 'loadtest: 재실행으로 돌린다', NULL, 'loadtest', NOW(6), NOW(6));
''')

print('\n'.join(out))
