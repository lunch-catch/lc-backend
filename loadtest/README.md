# 부하 시험

부하 생성기가 받아 쓰는 시나리오와 데이터 생성기를 둔다. 부하 생성기를 띄우고 내리는 일은 lc-infra 의 `scripts/loadtest-box.sh` 가 한다.

| 경로 | 하는 일 |
|---|---|
| `lib/token.js` | 회원과 관리자의 Access Token 을 `JWT_SECRET` 으로 서명한다 |
| `data/gen_adserving.py` | 광고 서빙 시험의 원천 데이터 SQL 을 만든다. 회원, 점주, 가게, 캠페인, 충전과 그날 실패로 둔 00:00 묶음이다 |
| `adserving/prepare.js` | 관리자 재실행 API 로 그날 00:00 묶음을 다시 돌려 예약과 그날 후보를 만든다 |
| `adserving/feed_impression.js` | 피드를 받고 받은 카드마다 노출을 보낸다 |

## 광고 서빙 시험 돌리기

앱은 `loadtest` 프로필로 띄운다. 결제가 토스 대신 목을 쓴다.

```bash
D=2026-10-27
python3 loadtest/data/gen_adserving.py --date $D --members 6000 --stores 200 > build/loadtest-adserving.sql
mysql -h <DB 호스트> -u <사용자> -p <스키마> < build/loadtest-adserving.sql

k6 run -e BASE_URL=<앱 주소> -e JWT_SECRET=<앱과 같은 값> -e BUSINESS_DATE=$D loadtest/adserving/prepare.js
k6 run -e BASE_URL=<앱 주소> -e JWT_SECRET=<앱과 같은 값> -e FEED_RATE=500 -e DURATION=5m \
       --summary-export build/loadtest-summary.json loadtest/adserving/feed_impression.js
```

- 생성기는 3,000만 대역의 행과 그날 배치 기록을 지우고 다시 넣는다. 부하 시험 DB 와 로컬에만 넣는다.
- 그날 배치 기록을 지우므로 `--date` 는 시험하는 날이다. 앱이 판단하는 영업일(Asia/Seoul)과 같아야 한다.
- 한 회원은 60초에 피드를 6번까지 받는다. `--members` 와 `MEMBERS` 는 `FEED_RATE` x 10 보다 크게 둔다.
- 노출 요청 수는 피드 요청 수 x 카드 수(최대 10)다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `FEED_RATE` | 500 | 초당 피드 요청 수 |
| `DURATION` | 5m | 피드 요청을 보내는 시간 |
| `MEMBERS` | 6000 | 돌려 쓰는 회원 수. 생성기의 `--members` 와 같게 둔다 |
| `MEMBER_BASE` | 30000001 | 첫 회원 번호 |

## 결과를 남기는 곳

버전을 비교하려면 측정한 커밋과 조건을 함께 남긴다. 결과는 `docs/troubleshooting/{도메인}/` 에 버전마다 두고, 비교할 지표는 그 도메인의 설계서가 갖는다. 광고 서빙은 [요청 중 쓰기 설계](../docs/design/adserving/adserving_요청_중_쓰기_설계.md)의 "버전을 비교하는 지표" 다.
