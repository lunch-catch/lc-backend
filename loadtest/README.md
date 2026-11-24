# 부하 시험

**처음이면 lc-infra 의 [팀원 부하 시험 가이드](https://github.com/lunch-catch/lc-infra/blob/main/docs/wiki/팀원-부하시험-가이드.md)부터 본다.** 자기 AWS 계정 준비부터 시험, 그라파나로 보기, 결과 남기기, 내리기까지의 순서를 갖고 단계마다 이 문서와 다른 문서를 가리킨다.

부하 생성기가 받아 쓰는 시나리오, 토큰 서명, 데이터 생성기를 둔다. 도메인마다 무엇을 어떤 조건으로 재는지는 그 도메인의 부하 시험 문서가 갖는다.

| 도메인 | 문서 |
|---|---|
| 광고 서빙 | [`docs/design/adserving/loadtest/`](../docs/design/adserving/loadtest/adserving_부하_시험.md) |
| 쿠폰 발급 | [`docs/design/coupon/loadtest/`](../docs/design/coupon/loadtest/coupon-load-test-summary.md) |

부하 시험 환경을 띄우고 내리는 일은 lc-infra 가 갖는다(`docs/wiki/팀원-부하시험-환경.md`, `scripts/member-env.sh`).

## 디렉터리

**도메인 하나가 디렉터리 하나를 갖는다.** 그 도메인의 시나리오와 생성기와 실행 스크립트를 함께 둔다. 종류로 나누지 않는다.

| 경로 | 하는 일 |
|---|---|
| `{도메인}/` | 그 도메인의 k6 시나리오, 시험 데이터 생성기, 실행 스크립트 |
| `{도메인}/run.sh` | lc-infra 로 띄운 환경에서 데이터 넣기, 회차 실행, 지표 모으기를 돌린다 |
| `{도메인}/gen_data.py` | 그 도메인의 시험 데이터 SQL 을 만든다 |
| `lib/` | 여러 도메인이 함께 쓰는 것. `token.js` 가 회원, 점주, 관리자의 Access Token 을 `JWT_SECRET` 으로 서명한다. `common.sh` 는 실행 스크립트가 불러 쓰는 공통 함수다 |

지금 있는 도메인은 `adserving/` 와 `billing/` 이다. `billing/` 은 생성기와 실행 스크립트 대신 `seed.sql` 과 `verify.sql` 을 둔다. `seed.sql` 은 시험 데이터를 넣고 `verify.sql` 은 시험이 끝난 뒤 충전이 맞는지 본다.

## 공통 규칙

- 앱은 `loadtest` 프로필로 띄운다. 결제가 토스 대신 목을 쓴다.
- 생성기의 SQL 은 부하 시험 DB 와 로컬에만 넣는다. 정해진 번호 대역의 행과 그날 배치 기록을 지우고 다시 넣는다.
- 버전을 비교하려면 측정한 커밋과 조건을 함께 남긴다. 결과는 측정 조건과 같은 자리인 `docs/design/{도메인}/loadtest/` 에 둔다. 파일을 몇 개로 나눌지는 그 도메인이 정한다.

## 회차 순서

버전을 비교하는 시험은 도메인과 상관없이 버전마다 아래 순서로 돈다(#1270). 회차 사이에 JVM 을 다시 띄우지 않는다.

```text
(앱 대수 고정) -> switch -> reset -> prepare -> warmup -> 측정 3회 -> 행 수 대조 -> 멈춤 -> 진단 -> 멈춤
```

도메인의 `run.sh version <버전>` 하나가 이 순서를 돈다(#1306). 순서와 멈춤은 공통의 `run_version` 이 갖는다.

- 측정 3회와 행 수 대조가 끝나면 멈춘다. 요약과 이어서 할 명령을 출력하고 끝나며, 입력을 기다리지 않는다. 진단 회차를 돌릴지 사람이 정하고, 정하면 `--from diag` 로 다시 부른다
- 진단 회차(프로파일링을 붙인 회차 하나)가 끝나면 다시 멈춘다. 다음 버전으로 갈지 사람이 정한다
- 무효 회차(3), 캡처 실패(4), 행 수 어긋남(5)이 나오면 그 자리에서 멈추고 다시 잴 명령을 출력한다
- 부하 시험 환경에서는 앱 오토스케일링 그룹을 `APP_COUNT`(3)대로 고정하고 다 설 때까지 기다린다. 로컬은 건너뛴다
- 측정 회차부터 트레이싱을 붙인다(`switch --tracing <버전>`, #1177)
- 캡처는 버전 폴더(`version_dir`, 예: `docs/design/adserving/loadtest/impression-v1/`) 아래 회차 이름의 폴더에 남는다. 유효한 측정 회차마다 결과 문서의 그 버전 절이 다시 쓰인다(아래 `결과 문서` 절, #1326). 로컬은 `build/loadtest-results/` 에 둔다

도메인의 `run.sh` 가 정하는 것은 `version_bands`(워밍업, 측정 3회, 진단의 회원 대역), `version_dir`(결과 문서 옆의 버전 폴더), `domain_rows_check`(행 수가 맞는지), `ROWS_WAIT`(행 수를 세기 전에 기다릴 초), `PREPARE_STEP`(prepare 를 부를지)다.

| 단계 | 하는 일 |
|---|---|
| `switch` | 앱과 배치를 그 버전의 이미지로 다시 띄운다 |
| `reset` | 앞 회차와 앞 버전이 남긴 것을 지운다. 앱의 데이터베이스를 지우고 다시 만든 뒤 앱과 배치를 다시 띄워, Flyway 가 표와 기준 데이터를 처음부터 만든다. 그 뒤 캐시를 비우고 시드를 넣는다(#1296). 무엇을 넣을지는 도메인의 `run.sh` 가 정한다 |
| `prepare` | 그날 배치처럼 측정 전에 있어야 하는 것을 만든다. 도메인마다 다르고, 필요 없으면 건너뛴다 |
| `warmup` | 정한 시간을 데운다(아래 `워밍업` 절). 결과에 적지 않는다 |
| 측정 3회 | 회원 대역을 바꿔 세 번 재고 평균한다. 대역은 도메인의 부하 시험 문서가 정한다 |

- `reset` 을 건너뛰면 기록 표가 쌓여 뒤 회차와 뒤 버전이 부푼다. 로컬에서 이어 돌렸을 때 피드 평균이 약 3.3배가 됐다(#1088).
- 표마다 시험 범위를 골라 지우면 시드의 목록에 없는 표가 버전 사이에 쌓인다. 로컬에서 분석의 `event_log` 가 약 518만 행, 쿠폰 재고가 약 377만 행까지 쌓였다(#1296). 그래서 데이터베이스째 다시 만든다.
- MySQL 서버는 다시 띄우지 않는다. 부하 시험 DB 는 RDS 라 서버를 되돌리기 어렵고, 버퍼 풀은 `warmup` 에서 다시 데워진다.
- `reset` 한 번은 로컬에서 43 ~ 148초였다. 대부분은 앱과 배치가 다시 떠 Flyway 가 빈 데이터베이스에 표를 만드는 시간이다. MySQL, Valkey, 앱, 배치만 띄웠을 때 43초, 관측 도구까지 모두 띄웠을 때 148초였다(#1296).

## 측정 계획

버전 여럿을 차례로 재는 시험은 측정 계획 파일 하나로 돈다(#1307). 도메인의 `run.sh plan <계획 파일> [--diag|--skip-diag]` 이 부를 때마다 다음 걸음 하나를 간다. 승인을 받는 자리마다 멈추기 때문이다.

```text
plan            -> 다음 버전의 측정(version) -> 멈춤: 진단을 돌릴지 정한다
plan --diag     -> 그 버전의 진단           -> 멈춤: 다음 버전으로 갈지 정한다
plan --skip-diag -> 진단을 건너뜀            -> 멈춤: 다음 버전으로 갈지 정한다
plan            -> 다음 버전의 측정 ...
```

계획 파일은 한 줄에 버전 하나다. `#` 으로 시작하는 줄과 빈 줄은 건너뛴다.

```text
<버전 이름> <커밋, 태그나 SHA> [ref=<LOADTEST_REF>] [version 의 선택지...]
```

- 커밋이 `TBD` 인 줄에 닿으면 멈춘다. 측정 직전에 정할 커밋이다
- `ref` 는 그 버전의 시나리오와 시드 생성기를 받을 ref 다. 예전 커밋에 시나리오가 없을 때 준다
- 선택지는 `version` 과 같다(`--scenario`, `--no-pool`, `--env`, `--app-env`, `--batch-env`). 값에 공백을 넣지 않는다
- 어디까지 했는지는 `build/loadtest-plans/<계획 파일 이름>.<local|lt>.state` 에 남는다. 로컬과 부하 시험 환경은 따로 센다. 줄마다 버전, 단계(`measure-done`, `diag-done`, `diag-skipped`), 시작과 끝의 UTC 시각이다. 측정이 멈추면 다시 부를 때 그 버전의 측정부터 다시 한다
- 환경을 띄우고 내리는 일(lc-infra 의 `member-env.sh`)은 계획에 넣지 않는다. 내린 뒤에는 되돌릴 수 없어 사람이 정한다

## 실행 스크립트의 공통 부분

`lib/common.sh` 가 어느 도메인이든 같은 일을 갖는다. 도메인의 `run.sh` 는 데이터 넣기와 시나리오처럼 그 도메인만의 일을 갖고, 공통 부분은 이 파일을 불러 쓴다.

| 함수 | 하는 일 |
|---|---|
| `remote`, `instance`, `instances` | 역할로 서버를 찾아 SSM 으로 스크립트를 보내고 끝날 때까지 기다린다 |
| `reset_state` | 회차의 출발점을 만든다. 앱의 데이터베이스를 다시 만들고 앱과 배치를 다시 띄워 Flyway 가 표와 기준 데이터를 처음부터 만들게 한 뒤 Valkey 를 비운다. 도메인의 `reset` 은 이것을 부른 뒤 자기 시드를 넣는다(#1296) |
| `loadgen` | 부하 생성기에서 스크립트를 돌린다. 스크립트는 `BASE_URL`, `JWT`, `OUT` 을 쓴다 |
| `metrics` | 회차 구간의 앱, DB, 캐시, 부하 생성기 지표를 모은다 |
| `prom_metrics` | 표준 입력으로 받은 `이름 PromQL` 줄들을 그 구간으로 Prometheus 에 묻는다(`metrics` 와 `after_run` 이 쓴다) |
| `after_run` | 회차 하나가 끝난 뒤의 일을 잇는다. `run` 의 출력에서 `window` 와 `hold` 를 읽어 `metrics` 를 부르고, lc-infra 의 `loadtest-snapshot.sh` 로 회차 이름을 붙여 캡처하고, 무효 회차를 판정한다. 무효면 걸린 조건과 값을 출력하고 3 으로 끝난다(#1305). 워밍업 회차는 건너뛴다 |
| `require_snapshot` | 회차를 돌기 전에 `LC_INFRA_DIR` 의 캡처 스크립트가 있는지 본다 |
| `run_version`, `pin_app_count` | 버전 하나를 회차 순서대로 돌고 멈춘다(위 `회차 순서` 절). 부하 시험 환경에서는 앱 대수를 고정한다(#1306) |
| `run_plan` | 측정 계획의 다음 걸음 하나를 간다(위 `측정 계획` 절, #1307) |
| `record_result` | 유효한 측정 회차의 핵심 수치와 서버 지표를 회차 폴더의 `result.json` 에 남기고, 결과 문서의 그 버전 절을 다시 써 커밋한다(아래 `결과 문서` 절, #1326) |
| `record_warnings` | 회차 구간의 앱과 배치의 지표 수집이 빠진 칸 수를 먼저 출력하고, 0 이 아니면 경고한다. 이어서 경고와 오류를 이벤트별로 세어 그 칸 수와 함께 결과 문서에 적고 커밋한다 |
| `prom_value` | Prometheus 에 식 하나를 지금 시각으로 묻는다(`warmup` 이 쓴다) |
| `warmup` | 도메인의 `run` 을 정한 시간 돌리고, 끝 3분의 앱 CPU 흔들림으로 덜 데워졌는지 가린다(아래 `워밍업` 절) |
| `profile` | 앱 서버에 프로파일링을 붙여 Pyroscope 로 보낸다 |
| `full_sha`, `image_exists` | 이미지 커밋을 40자로 늘리고 GHCR 에 있는지 본다 |
| `app_override`, `local_override`, `local_switch` | 앱과 배치의 compose 덮어쓰기 파일(트레이싱 에이전트, 연결 풀 끔, `--env`, `--app-env`, `--batch-env` 의 환경변수)을 만들고, 로컬 앱과 배치를 그것으로 다시 띄운다 |

새 도메인은 `run.sh` 의 앞에서 아래 값을 정하고 이 파일을 불러 쓴다. `loadtest/adserving/run.sh` 가 예다.

```bash
LOCAL=""
[ "${1:-}" = --local ] && { LOCAL=1; shift; }
DOMAIN_EVENT_PREFIX=COUPON_                                       # 그 도메인 로그의 event 접두사
RESULT_DOC=docs/design/coupon/loadtest/쿠폰_부하_시험_결과.md       # 경고 로그를 적을 결과 문서. 자리와 이름은 AGENTS.md 의 docs 표가 정한다
DOMAIN_METRICS='
issued_inc sum(increase(coupon_issued_total[$W]))
'                                                                 # metrics 가 더 묻는 지표(예). 없으면 비운다
DOMAIN_SNAPSHOT=coupon                                            # 캡처가 대시보드의 도메인 폴더에서 찍을 도메인. 없으면 도메인 폴더를 모두 찍는다
. "$(dirname "$0")/../lib/common.sh"
```

- 회차를 돌린 뒤 `record_warnings <회차 이름> <시작 ms> <끝 ms>` 를 부르면 경고 로그가 남는다. 고르는 조건은 그라파나의 경고 로그 패널(lunch-catch/lc-infra#63)과 같다.
- 그다음 `after_run <회차 이름> <run 의 출력 파일>` 을 부르면 지표, 캡처, 무효 판정이 이어서 돈다. 출력 파일에는 `window <시작> <끝>` 과 `hold <시작> <끝>`(ms) 줄이 있어야 한다. 무효면 3 을 돌려주므로 도메인의 `run` 은 그 값으로 끝난다. 회차를 돌기 전에 `require_snapshot <회차 이름>` 을 부른다.
- 무효 조건은 도메인과 상관없는 것만 공통이 갖는다. 유지 구간의 부하 생성기 1분 평균 CPU 가 80% 를 넘음, ElastiCache 의 CPU 크레딧이 바닥나거나 네트워크 허용량을 넘긴 패킷이 생김, k6 가 목표 부하 구간 끝 전에 멈춤이다. RDS 는 Unlimited 라 크레딧이 바닥나도 무효로 보지 않는다. 로컬은 부하 생성기와 ElastiCache 지표가 없어 k6 조건만 본다.
- 캡처는 `LC_INFRA_DIR`(기본은 이 저장소 옆의 `lc-infra`)의 스크립트로 한다. 캡처를 결과 문서 옆의 버전 폴더에 두려면 `SNAPSHOT_OUT` 에 그 폴더를 준다. 그 아래에 회차 폴더(`v1-run1/` 등)가 생기고, 안은 `dashboards/`(그라파나의 폴더와 제목), `traces/`(트레이스마다 화면과 로그)로 나뉜다(lunch-catch/lc-infra#191). 캡처가 실패하면 `after_run` 은 4 로 끝난다.
- 같은 표에 앱과 배치의 지표 수집이 빠진 칸(15초) 수를 수준 `수집` 의 줄(`APP_SCRAPE_MISSED`, `BATCH_SCRAPE_MISSED`)로 늘 적는다. 0 이 아니면 그 칸의 서버 지표가 통째로 비어, 그 구간의 그래프와 판정을 그만큼 덜 믿는다(#1222).
- 빠진 칸은 받아야 할 칸에서 수집에 성공한 칸을 뺀 수다. 수집이 실패한 칸과 표본이 아예 없는 칸을 함께 센다. 로컬은 Prometheus 가 앱과 같은 Docker 가상 머신에 있어, 가상 머신이 멈추면 실패도 기록되지 않는다(#1267).
- 빠진 칸 수는 결과에 적지 않는 회차(`-warmup` 등)와 경고 로그를 묻지 못한 회차에도 출력한다. 0 이 아니면 `WARN` 을 띄운다. 그 회차의 수치는 다른 회차와 견주지 않고 원인을 본 뒤 다시 돈다.
- 결과 문서에 제목에 `경고 로그` 가 든 절을 먼저 둔다. 절이 없으면 적지 않고 안내만 한다. 표는 처음 적을 때 만든다.
- 커밋은 결과 문서 하나만 지금 브랜치에 한다. 다른 파일이 스테이징돼 있으면 커밋하지 않는다. 시험용 브랜치에서 돌린다.

## 결과 문서

`version` 으로 돈 측정 회차가 유효로 끝나면 `after_run` 이 `record_result` 를 불러 결과 문서를 채운다(#1326). 회차가 끝나면 사람이 옮길 것이 없게 한다.

- 결과 문서에서 제목에 `회차별 결과` 가 든 절 안에 버전마다 절 하나(`### v1`)를 둔다. `<!-- result:<버전 폴더> -->` 와 `<!-- /result:<버전 폴더> -->` 사이만 다시 쓰고, 그 밖에 사람이 쓴 글은 건드리지 않는다. 절이 없으면 적지 않고 안내만 한다
- 회차의 수치는 회차 폴더의 `result.json` 에 남긴다. 버전 절은 그 버전 폴더의 회차들을 모두 읽어 매번 새로 쓴다. 그래서 세 번째 회차가 끝나면 3회 묶음이 생기고, 한 회차를 다시 재면 그 줄만 바뀐다
- 무효 회차, 캡처가 실패한 회차, 워밍업, 진단 회차(`-profile`)는 적지 않는다. `version` 없이 `run` 만 부르면 버전 폴더가 없어 적지 않는다

| 버전 절의 내용 | 무엇을 적나 | 3회 묶음 |
|---|---|---|
| 핵심 수치 | API 마다 초당 요청, p50 / p95 / p99, 오류율(5xx 비율). 앱 지표로 유지 구간만 센다. 분위수는 uri 만 단 히스토그램(`http_server_requests_histogram_seconds`)이라 같은 uri 의 메서드를 합쳐 센다 | 평균 |
| k6 가 본 실패 | k6 꼬리표(`api`)마다 유지 구간의 요청, 실패, 실패율. 통과 기준의 오류율은 이 값으로 판정한다. 앱의 5xx 에 들지 않는 시간 초과와 앱에 닿기 전의 실패가 든다(#1329) | 평균 |
| 기준 초과 | 회차마다 셋. API(p99 와 기준, 판정, 가장 오래 걸린 트레이스), 요청 경로의 메서드(그 API 아래에서 그 API 의 기준을 넘은 자식 스팬. TraceQL 로 남은 트레이스만 센다), 백그라운드 작업(릴레이의 발행 지연 p99, 워커의 가장 오래 기다린 일, cron 의 실행 한 번). 기준은 앱의 `overrun_threshold_seconds` 이고, 이름별 값이 있으면 설정, 없으면 기본값이라고 적는다. 트레이싱을 켜지 않았으면 메서드 대신 한 줄을 적는다(#1339) | 없다 |
| 상세 | `metrics` 가 모은 공통 지표와 도메인 지표(`DOMAIN_METRICS`). 줄이 지표, 칸이 회차다. 지표 이름 그대로 적는다 | 이름이 `_max`, `_inc`(건수), `_max_pct` 로 끝나거나 CPU 최대, 허용량 초과면 가장 큰 값, 크레딧과 `_min` 은 가장 작은 값, 나머지(평균, 초당)는 평균 |
| 그림 | 회차마다 API 대시보드, 도메인 대시보드(`DOMAIN_SNAPSHOT`), 가장 느린 트레이스 화면 한 장을 펼친다 | 없다 |
| 링크 | 나머지 대시보드를 회차 폴더로 링크한다 | 없다 |
| 트레이스 | 회차의 `traces.txt` 를 옮긴다(대상, 판정 값, 걸린 시간, 트레이스 ID). 줄마다 트레이스 화면, 로그 화면, 로그 글을 링크하고 그 로그의 경고와 오류 이벤트 이름을 적는다. 고르지 못했으면 캡처가 남긴 까닭을 적는다 | 없다 |

- k6 가 본 실패는 도메인의 `run` 이 출력에 남긴 `k6-summary <k6 요약 JSON>` 한 줄에서 읽는다. k6 를 `--summary-export` 로 돌리고, 시나리오가 `http_req_failed{api:<이름>,phase:hold}` 로 임계값을 두어야 그 칸이 요약에 생긴다. Prometheus 로 세지 않는 것은 회차 도중에 처음 생긴 실패 계열의 첫 값을 `increase` 가 세지 못해 실패가 적게 잡히기 때문이다
- 부하 시험 환경은 결과 문서와 그 회차 폴더를 지금 브랜치에 커밋한다. 다른 파일이 스테이징돼 있으면 커밋하지 않는다
- 로컬은 결과 문서 대신 `build/loadtest-results/` 에 결과 문서의 사본을 만들어 적는다. 회차 폴더도 그 옆에 남는다. 커밋하지 않는다
- 진단 회차(`-profile`)는 제목에 `진단` 이 든 절에 버전마다 적는다(#1341). 측정 회차의 버전 절과 섞지 않는다. 진단 회차의 수치에는 프로파일링의 비용이 섞여 측정 회차와 견주지 않는다
  - Call Tree wall(앱, 배치)을 펼치고 cpu, Redis 지표, 원본 파일(#1335 의 보관), 대시보드를 링크한다
  - 트레이스 표: `traces.txt` 를 옮기고 트레이스마다 가장 오래 걸린 단계(루트를 뺀 가장 긴 스팬)를 `trace.json` 에서 붙인다
  - 프로파일 표: 서비스(앱 `lunchcatch`, 배치 `lunchcatch-batch`)마다 wall 과 cpu 의 Self 상위 5개를 합쳐 함수마다 두 비율을 나란히 둔다. 표는 둘이다. 맨 끝 프레임 기준은 그라파나 Call Tree 의 Self 와 같고, 자바 함수 기준은 네이티브 프레임(`poll` 등)의 몫을 그것을 부른 가장 가까운 자바 함수에 붙인다
- 진단 회차는 앞 버전의 진단 회차와 견준다(#1343). 앞 버전은 측정 계획에서 같은 시나리오(`--scenario`)인 바로 앞 줄이고, `version` 을 손으로 부를 때는 `--baseline <앞 버전>` 으로 준다
  - 비교는 두 회차 폴더의 프로파일 파일로 센다. 같은 날이든 다른 날이든, 환경이 떠 있든 아니든 같다. 고친 함수의 몫(그 함수 아래 전체, 앞 → 이 버전), 고친 함수 밖에서 Self 몫(자바 함수 기준)이 가장 많이 늘거나 준 함수 5개씩, 두 회차의 조건(`condition.json`, 구간 길이) 차이를 적는다
  - Diff 그림은 앞 버전이 이 환경의 Pyroscope 에 남아 있으면 진단 회차의 캡처가 찍는다(`--baseline`). 없으면 lc-infra 의 `loadtest-diff.sh` 로 노트북에서 찍는 명령을 진단 절에 적는다
  - 고친 함수 후보는 측정이 끝나 멈출 때 앞 버전 커밋과 이 버전 커밋의 `git diff` 에서 뽑아 버전 폴더의 `fixed-candidates.txt` 에 남긴다. 바뀐 줄마다 두 커밋에서 그 줄을 감싼 메서드를 찾아 `클래스.메서드` 로 읽는다. `package`, `import`, 빈 줄, 주석만 바뀐 줄은 뺀다. 메서드 밖(필드 등)의 변경은 후보가 되지 않는다. 고른 것을 진단 명령에 `--fixed <함수>` 로 붙인다(계획이면 `plan <계획> --diag --fixed <함수>`)
- 진단 회차는 앱 서버가 Pyroscope 로 보낸 묶음마다 원래 구간(`batch <서비스> <종류> <from> <until>`)을 회차 폴더의 `profile-batches.txt` 에 남긴다. 프로파일 보관이 그 `until` 을 적어 다시 올릴 때 원래 값을 쓴다(#1343). Pyroscope 는 묶음을 `from` 한 점에 찍고 `until` 은 돌려주지 않는다
- 로컬의 결과 문서 사본은 처음에 원본을 복사하고, 그 뒤 원본에 새로 생긴 절(`회차별 결과`, `진단`)은 사본 끝에 더한다. 부하 시험 환경은 그 회차 폴더(프로파일 파일 포함)를 따로 커밋한다(#1335). 진단 회차도 측정 회차와 같은 대시보드를 찍는다(#1335). 그 화면에는 프로파일링 비용이 섞여 측정 회차와 견주지 않는다

## 로컬에서 돌리기

lc-infra 의 로컬 구성(`observability/local`)에 앱과 관측 스택을 띄우고 부하 시험 환경과 같은 절차를 돌린다. 수치는 노트북의 것이라 결과 문서에 적지 않는다. 동작과 대시보드를 확인하는 데 쓴다.

```bash
# 1. 로컬 구성을 띄운다(lc-infra 의 docs/wiki/팀원-도메인-모니터링.md)
./gradlew bootJar -x test
# 배치를 따로 띄우는 구성이다. 로컬 switch 가 이 구성으로 앱과 배치를 다시 띄운다
(cd <lc-infra>/observability/local && docker compose -f compose.yaml -f compose.lt-like.yaml up -d --build)

# 2. 같은 인자로 돌린다. LC_INFRA_DIR 은 lc-infra 경로다. 서명 키를 그 .env 에서 읽는다
export LC_INFRA_DIR=<lc-infra>
loadtest/adserving/run.sh --local seed
loadtest/adserving/run.sh --local prepare
# run 이 끝나면 그 구간의 지표, 대시보드와 트레이스 캡처(lc-infra 의 loadtest-runs/), 무효 판정이 이어서 돈다(#1305)
loadtest/adserving/run.sh --local run local-run1 30000001 20 1m

# 3. 내린다
(cd <lc-infra>/observability/local && docker compose down -v)
```

- 시나리오와 생성기는 지금 작업 트리의 것을 쓴다. k6 는 이 기계에서 돌고 지표를 로컬 Prometheus 로 보내 부하 시험 대시보드가 같은 화면으로 채워진다.
- `rows`, `drain`, `clean` 처럼 AWS 에만 있는 일은 로컬에서 막는다. `reset` 은 로컬 앱과 배치를 내리고 로컬 데이터베이스를 지우고 다시 만든 뒤 다시 띄우고, 로컬 Valkey 를 비우고 로컬 시드를 넣어, AWS 와 같은 순서(`reset` → `prepare` → 워밍업 → 측정)로 잰다. `switch` 는 커밋 없이 `[--tracing <이름>] [--no-pool] [--env|--app-env|--batch-env KEY=VALUE]...` 를 받아 로컬 앱과 배치를 다시 띄운다. 배치는 lc-infra 의 `compose.lt-like.yaml` 로 따로 뜬다.
- 초당 피드는 노트북에 맞춰 낮춘다. 20 이면 초당 요청이 약 200 이다.
- 로컬 `switch` 는 Alloy 와 Tempo 를 다시 띄워 쌓인 스팬과 트레이스를 비운다. 관측 도구가 앱과 같은 Docker 가상 머신에 있어, 트레이싱 회차를 이어 돌리면 둘이 쌓여 앱과 메모리를 나눠 쓴다(#1267).
- `run` 만 부르면 결과 문서의 사본에 적지 않는다. 적을 버전 폴더가 없어서다. `version` 으로 돌거나 `SNAPSHOT_OUT=$PWD/build/loadtest-results/<시나리오>-<버전>` 을 주고 `run` 을 부르면 `build/loadtest-results/` 의 사본에 회차별 결과와 기준 초과 표가 적힌다.

### 기준 초과 표를 로컬에서 확인하기

일부러 느리게 만든 회차로 기준을 넘은 API, 메서드, 백그라운드 작업이 표에 나오는지 본다(#1128). 앱 코드는 고치지 않는다.

```bash
# 트레이싱을 붙이고 피드의 기준을 300ms 에서 200ms 로 바꿔 띄운다. 기준은 앱의 설정(application.yml 의 lunchcatch.observability.targets)을 따른다
loadtest/adserving/run.sh --local switch --tracing slow \
  --env 'SPRING_APPLICATION_JSON={"lunchcatch":{"observability":{"targets":{"api":{"[GET /v1/feed]":"200ms"}}}}}'

# 유지 구간 동안 6초마다 Valkey 를 1초 멈춘다(램프업 1분 뒤부터)
( sleep 70; end=$(( $(date +%s) + 55 )); while [ "$(date +%s)" -lt "$end" ]; do
    docker pause lunchcatch-local-valkey-1 > /dev/null; sleep 1; docker unpause lunchcatch-local-valkey-1 > /dev/null; sleep 5
  done ) &
SNAPSHOT_OUT=$PWD/build/loadtest-results/impression-slow loadtest/adserving/run.sh --local run slow-run1 30000001 15 1m
wait

# 끝나면 기준과 트레이싱을 되돌린다
loadtest/adserving/run.sh --local switch
```

- 기준은 앱과 배치가 내보낸 값 가운데 큰 값으로 판정한다. 둘은 같은 설정 파일을 읽는다. 위처럼 바꿀 때는 `--env` 로 둘 다 바꾼다. `--app-env` 로 앱만 바꾸면 배치의 300ms 로 판정된다.
- 2026-11-24 에 돌렸을 때 피드가 200.0ms(설정)에 p99 3.08초로 넘었고, 메서드 표에 그 기준을 넘은 DB 쿼리 7줄, 백그라운드 작업 표에 노출 릴레이와 노출 저장 워커가 넘음으로 나왔다. Valkey 명령은 스팬이 아니라 메서드 표에 나오지 않는다. 멈춘 동안 밀린 요청이 연결 풀을 기다려 DB 쿼리가 길어진다.

## 워밍업

`warmup` 은 목표 부하에 닿은 뒤 5분을 정한 시간으로 데운다(#1267). 측정 회차 앞에 버전마다 한 번 돌린다. 판정은 `lib/common.sh` 의 `warmup` 함수가 갖고, 도메인의 `run.sh` 는 그 도메인의 `run` 에 넘길 인자만 정한다(#1270).

```bash
loadtest/adserving/run.sh warmup v1-warmup 30030001
loadtest/adserving/run.sh --local warmup local-warmup 30030001 15
```

- 회차마다 같은 시간을 데워 버전 사이의 조건을 맞춘다. 5분은 로컬에서 길게 돌렸을 때 앱 CPU 가 트레이싱 켬과 끔 모두 목표 부하 3분째부터 평평했던 것에 여유를 더한 값이다.
- `run` 이 끝나면 그 출력의 목표 부하 구간 끝 5초 전을 기준으로(PromQL `@`) 직전 3분의 앱 CPU 흔들림(1분 평균의 최대와 최소의 차)과 JIT 컴파일(`jvm_compilation_time_ms_total` 의 3분 비율, 가장 높은 앱)을 묻고 `warmup-result <이름> hold 300s cpu_spread=<값> jit=<값>` 을 낸다.
- k6 가 정한 시간 전에 끝나거나 `run` 이 실패하면 `warmup-result <이름> failed rc=<값>` 을 내고 실패로 끝난다. 부하 없이 지나간 구간을 데운 것으로 적지 않는다.
- CPU 흔들림이 5%p 를 넘으면 `WARN` 을 낸다. 그 버전의 결과 줄에 "덜 데워짐" 과 그때의 값을 적는다. JIT 은 적기만 한다. 트레이싱 에이전트를 붙이면 부하가 일정해도 초당 10 ~ 25ms 로 남아 끝내는 조건으로 쓸 수 없다.
- 이름은 `-warmup` 으로 끝낸다. 결과 문서에 적지 않는 회차다.
- 트레이싱을 켠 앱은 끈 앱보다 늦게 데워진다. 에이전트가 계측 코드를 끼워 넣어 컴파일할 코드가 늘기 때문이다.

새 도메인은 `run.sh` 에 아래 분기를 둔다. `warmup` 은 넘겨받은 `run` 명령을 그대로 돌리므로, 그 `run` 은 두 가지를 지킨다.

- 목표 부하 구간을 `WARMUP_HOLD` 초로 돈다.
- 출력에 `hold <시작> <끝>`(목표 부하 구간)과 `window <시작> <끝>`(k6 가 실제로 돈 구간) 줄을 밀리초로 낸다. `loadtest/adserving/run.sh` 의 `run` 이 예다.

```bash
  warmup|local-warmup)
    name="${2:?회차 이름을 준다}"; base="${3:?회원 시작 번호를 준다}"
    shift 3
    warmup "$name" "$0" ${LOCAL:+--local} run "$name" "$base" "${WARMUP_HOLD}s" "$@"
    ;;
```

## 연결 풀을 끄고 재기

같은 커밋을 Valkey 연결 풀 켬과 끔으로 재 풀만의 효과를 가른다. `switch` 에 `--no-pool` 을 주면 `SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED=false` 를 넣어 다시 띄운다. 주지 않으면 켠다.

```bash
loadtest/adserving/run.sh switch <이미지> --no-pool
loadtest/adserving/run.sh --local switch --no-pool
```

- 트레이싱과 같은 `compose.override.yaml` 에 들어간다. `--tracing` 과 함께 줄 수 있다.
- 어느 쪽으로 떴는지 `switch` 의 출력 끝에 `pool on` 이나 `SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED=false` 로 나온다.

## 조정 값 바꿔 재기

광고 서빙의 버퍼, 릴레이, 캐시 값은 `application.yml` 의 `adserving.*` 설정이다(#1170). `switch` 에 `--env KEY=VALUE` 를 주면 그 환경변수를 넣어 다시 띄운다. 여러 번 줄 수 있고, 다시 빌드하지 않는다.

```bash
loadtest/adserving/run.sh switch <이미지> --env ADSERVING_IMPRESSION_WRITEBUFFER_BATCHSIZE=1000
loadtest/adserving/run.sh --local switch --env ADSERVING_CACHE_CANDIDATEMAXAGE=10s
```

- 이름은 설정 키를 대문자로 바꾸고 점을 `_` 로, `-` 를 빼서 만든다. `adserving.impression.write-buffer.batch-size` 는 `ADSERVING_IMPRESSION_WRITEBUFFER_BATCHSIZE` 다.
- 설정 키와 기본값, 값을 고른 이유는 `application.yml` 의 `adserving:` 에 있다. 값끼리 맞물리는 것(묶음 <= 용량, 첫 백오프 <= 최대 백오프, 릴레이의 임대 > 주기)을 어기면 앱이 뜨지 않는다.
- 트레이싱, 풀 끔과 같은 `compose.override.yaml` 에 들어간다. `--env` 없이 다시 `switch` 하면 그 값이 빠진다.

## 대기 노출만 비우고 재기

노출 릴레이와 정산의 묶음 차감(`deductAll`)만 잰다(#1198). 노출 API 를 부르지 않고, 그날 유효 노출을 미리 넣어 릴레이가 다 비울 때까지 기다린다.

```bash
loadtest/adserving/run.sh seed
loadtest/adserving/run.sh prepare
loadtest/adserving/run.sh drain drain-a1 50000
loadtest/adserving/run.sh clean
loadtest/adserving/run.sh drain drain-b1 50000
```

- 앱 서버에서 노출 N건(기본 50,000)을 한 트랜잭션으로 넣고, 넣은 뒤부터 남은 노출이 0 이 될 때까지를 한 구간으로 잰다. 끝나면 걸린 초와 초당 건수를 내고 그 구간으로 `metrics` 를 묻는다.
- 노출은 시드의 캠페인 1,000개와 회원 5,000명에 고르게 나눈다. 캠페인 하루 예산이 커서 회차마다 같은 비율로 과금되어 원장에 DEDUCT 가 쓰인다. 그래서 `prepare` 로 그날 예약이 있어야 한다.
- 회차가 끝나면 밀린 노출이 0 이라 다음 회차로 넘어가지 않는다. 노출 API 부하와 섞어 회차를 이어 돌면 밀림이 넘어가 뒤 회차일수록 느려진다.
- 회차 사이에 `clean` 으로 비운다(#1283). 비우지 않고 이어 재면 커지는 원장에 DEDUCT 를 쓰는 비용이 회차마다 늘어, 같은 조건의 회차가 2 ~ 4배 벌어졌다(`docs/design/billing/loadtest/` 의 묶음 차감 부하 시험 결과).
- `clean` 은 노출과 판정을 TRUNCATE 한다. 원장은 그날 예약이 남아야 차감이 돌아 DEDUCT 가 아닌 행만 옮겨 두고 비운 뒤 되돌린다. 인덱스와 FK 는 그대로라 회차 앞에 붙이거나 지운 인덱스가 남는다. 다 비우면 dirty 페이지와 undo 가 가라앉을 때까지 기다린다. 2026-11-16 에는 노출 10만 건 회차 뒤에 비우는 데 31 ~ 38초 걸렸다.
- `drain` 은 노출이 한 건이라도 남아 있으면 넣지 않고 멈춘다. 1시간 안에 비우지 못해도 멈춘다.
- 도는 동안 남은 노출은 5초마다 인덱스만으로 센다. 행 본문까지 읽어 세면 회차 시작에 대기 노출 수만 행을 5초마다 읽어, 데이터가 버퍼 풀을 넘은 환경에서는 그 읽기가 차감을 늦춘다(#1198).
- 후보 예산 갱신(`CandidateBudgetRefreshJob`)이 매시 0, 10, 20 ... 분에 배치 두 대에서 돈다. 회차를 그 직후에 시작해 회차 안에 섞이는 횟수를 맞춘다.

## 앱과 배치에 다른 값 넣기

`--env` 는 앱과 배치에 같은 값을 넣는다. 앱에만 넣을 것은 `--app-env`, 배치에만 넣을 것은 `--batch-env` 로 준다(#1244). 섞어 줄 수 있고, 한 역할에 같은 키가 두 번 들어가면(`--env` 와 역할별을 함께 준 경우 포함) 띄우기 전에 멈춘다.

```bash
loadtest/adserving/run.sh switch <이미지> --app-env KEY=VALUE --batch-env KEY=VALUE
loadtest/adserving/run.sh --local switch --app-env KEY=VALUE --batch-env KEY=VALUE
```

- 드라이버 옵션처럼 대소문자를 지켜야 하는 키는 환경변수 이름으로 넘기면 소문자로 바뀐다. `SPRING_APPLICATION_JSON` 으로 넘긴다.
- #1088 은 예전 버전(v1 ~ v3c)의 이미지에 지금의 DB 시간 제한(#1217)을 넣고 잰다. 예전 이미지는 그 값을 갖고 있지 않아 버전마다 아래를 함께 준다.

```bash
loadtest/adserving/run.sh switch <이미지> --tracing <버전> \
  --app-env 'SPRING_APPLICATION_JSON={"spring":{"datasource":{"hikari":{"data-source-properties":{"socketTimeout":10000}}},"jpa":{"properties":{"jakarta.persistence.query.timeout":5000}},"jdbc":{"template":{"query-timeout":"5s"}},"transaction":{"default-timeout":"5s"}}}' \
  --batch-env 'SPRING_APPLICATION_JSON={"spring":{"datasource":{"hikari":{"data-source-properties":{"socketTimeout":300000}}},"jpa":{"properties":{"jakarta.persistence.query.timeout":120000}},"jdbc":{"template":{"query-timeout":"2m"}},"transaction":{"default-timeout":"-1s"}}}'
```

## 트레이싱

느린 요청 하나가 어느 단계에서 몇 ms 를 썼는지 본다. 앱 서버와 배치 서버에 OpenTelemetry Java 에이전트(`v2.32.0`)를 붙인다. 앱 코드는 바꾸지 않는다.

```bash
# 에이전트를 붙여 다시 띄운다. 이름이 트레이스의 run 라벨이 된다
loadtest/adserving/run.sh switch <이미지> --tracing v1
loadtest/adserving/run.sh run v1-run1 30030001 100 5m

# 떼려면 --tracing 없이 다시 띄운다
loadtest/adserving/run.sh switch <이미지>
```

- 앱 서버와 배치 서버의 `/opt/lunchcatch-lt` 에 에이전트와 `compose.override.yaml` 을 둔다. 배치 서버는 서비스 이름을 `lunchcatch-batch` 로 띄워, Alloy 가 배치의 작업 트레이스를 따로 고른다. compose 가 그 파일을 함께 읽어 lc-infra 의 설정을 고치지 않는다. `--tracing` 없이 띄우면 그 파일을 지운다. 로컬은 같은 파일을 `build/otel/` 에 만든다.
- 앱은 같은 compose 의 Alloy(`http://alloy:4318`)로 스팬을 보낸다. 남길 트레이스는 Alloy 의 테일 샘플링이 고른다. 규칙은 [트레이싱과 프로파일링 설계](../docs/observability/트레이싱과_프로파일링_설계.md) 4장에 있다.
- 우리 코드의 메서드에는 스팬을 만들지 않는다. I/O 스팬 사이의 빈 구간은 프로파일링으로 본다([트레이싱과 프로파일링 설계](../docs/observability/트레이싱과_프로파일링_설계.md) 3장).
- 라벨은 JVM 이 뜰 때 정해진다. 한 번 띄운 뒤의 회차는 같은 라벨이라 회차는 시각 구간으로 가린다.

**그라파나에서 보는 법:** Explore 에서 데이터 소스 `Tempo` 를 고르고 `{ resource.run = "v1" && name = "GET /v1/feed" && duration > 300ms }` 처럼 찾는다. 배치의 작업은 `{ resource.service.name = "lunchcatch-batch" && name = "ImpressionDispatchJob.dispatchPending" }` 처럼 찾는다. 트레이스를 열면 HTTP 와 DB 쿼리 스팬이 시간 순으로 보인다. 스팬에서 그 요청의 로그(Loki)와 프로파일(Pyroscope)로 넘어간다. 로그의 `trace_id` 에서도 그 트레이싱으로 넘어간다.

지킬 것:

- 측정 회차에도 켠다. 비용(앱 CPU 한도의 약 2.6%p)이 비용 한도 안이라 켠 채로 잰 수치를 결과에 쓴다([트레이싱과 프로파일링 설계](../docs/observability/트레이싱과_프로파일링_설계.md) 6장과 7장). 결과에 넣지 않을 회차만 이름을 `-tracing` 으로 끝낸다.
- 노출 저장과 서빙 로그 대기열은 응답 뒤에 돌아 트레이스가 거기서 끊긴다.
- 에이전트 판은 `v2.32.0` 으로 고정한다. 새 판은 나온 지 2주가 지난 뒤에 올린다.
- 트레이스에는 Valkey 명령의 스팬이 없다. 명령마다 스팬이 생겨 피드 요청 하나의 스팬 가운데 가장 많아 에이전트의 Lettuce 계측을 끈다(#1177). Valkey 명령의 시간은 앱 쪽 지표(`lettuce_command_completion_seconds`, 04 데이터 저장소의 `Valkey 명령 지연`, #1180)와 Redis 서버 지표(진단 캡처의 `diag-{회차}-redis.txt`)로 본다. 트레이스에서는 그 시간이 이름 없는 빈 구간이 된다.
- 로그와 트레이스는 로그의 `trace_id` 로 잇는다. 앱도 에이전트가 연 트레이스의 ID 를 같은 키에 넣어 두 값이 갈리지 않는다(#1300).
- 회차가 끝난 뒤의 캡처(lc-infra 의 `loadtest-snapshot.sh`)가 기준을 넘은 요청의 트레이스 화면과 그 로그를 함께 남긴다. 측정 회차도 같다(lunch-catch/lc-infra#187).

## 프로파일링

느린 요청이 어느 코드에서 시간을 쓰는지 본다. async-profiler(`v4.5`)를 앱 컨테이너에 넣어 붙인다. 앱을 다시 띄우지 않는다.

```bash
# 회차 도중에 붙인다. 기본은 60초, wall 이다
loadtest/adserving/run.sh profile v1-run1-profile 60 wall

# 회차 내내 붙인다. 이름을 -profile 로 끝낸다
loadtest/adserving/run.sh run v1-run1-profile 30030001 100 5m --profile wall

# cpu 와 wall 을 한 기록으로 함께 뜬다
loadtest/adserving/run.sh run v1-run1-profile 30030001 100 5m --profile both
```

| 모드 | 재는 것 | 간격 |
|---|---|---|
| `cpu` | 계산에 쓴 시간이다. 컨테이너 안에서는 `-e cpu` 가 막혀 `itimer` 로 뜬다 | 10ms. async-profiler 의 기본값이다 |
| `wall` | 대기까지 포함한 경과 시간이다. p99 를 줄이려면 이것을 먼저 본다 | 50ms. wall 모드의 기본값이다 |
| `both` | 둘을 한 기록(JFR)으로 함께 뜨고 나눠 보낸다. 같은 시간, 같은 부하의 둘을 견줘 계산과 대기를 가른다 | 위 둘과 같다 |

**그라파나에서 보는 법:** Drilldown -> Profiles 에서 서비스 `lunchcatch` 를 고르고, 프로파일 종류(`process_cpu` 나 `wall`)와 `run` 라벨로 그 회차를 고른다. 10초씩 끊어 보내 대시보드와 함께 10초 남짓 늦게 보인다. Split 과 Single 로 비교와 단독을, Top Table 과 Flame Graph 로 표와 그래프를 바꾼다.

**읽는 법:** Self 는 그 메서드 자기 코드의 시간이고 Total 은 부른 메서드까지 합친 시간이다. 시간은 그 구간의 합계라 호출 한 번의 시간은 나오지 않는다.

**보내기 전에 다듬는다.** 우리 코드가 일을 시작한 자리부터 남기고 그 아래의 톰캣, 스프링, 스레드 풀 프레임은 자른다. 시작 자리는 요청이면 컨트롤러(`FeedController.getFeed`), `@Scheduled` 작업이면 그 작업 메서드(`CandidateBudgetRefreshJob.refresh`), 워커면 처음 나오는 우리 프레임(`ImpressionWriteBuffer.runWorker`)이다. 쉬는 스레드(요청이 없는 톰캣 스레드, 다음 실행을 기다리는 스케줄러, 대기열을 기다리는 워커)는 버린다. 이름은 패키지를 빼고, 호출마다 되풀이되는 스프링 프록시와 AOP 프레임은 뺀다. 다듬지 않으면 컨트롤러까지 프레임이 100개 넘게 쌓여 이름이 잘린다. 패키지는 `com.lunchcatch` 와 `com.launchcatch`(#1060 전의 커밋) 둘 다 받는다.

**cpu 와 wall 은 회차가 끝난 뒤에 나눈다(#1345).** `both` 는 회차 동안 10초마다 JFR 만 떠 앱 컨테이너의 `/tmp` 에 쌓고, 회차가 끝나면 묶음마다 `jfrconv` 로 나누고 다듬어 원래 시각(`from`, `until`)으로 보낸 뒤 지운다. 회차 동안 나누면 그 2 ~ 3초는 뜨기를 쉬고, 나누는 자바 프로세스가 앱과 같은 컨테이너의 CPU 를 나눠 쓴다. 그래서 회차 도중에는 그라파나에서 볼 수 없고, `run` 은 다 보낸 뒤에 캡처한다. 나누는 때에 따른 비용은 [설계 문서 7장](../docs/observability/트레이싱과_프로파일링_설계.md#프로파일을-나누는-때로컬)이 갖는다.

지킬 것:

- 측정 회차와 나눈다. 붙인 동안은 표본을 뜨는 비용이 든다. 프로파일링 회차는 결과 표와 경고 로그 기록에 넣지 않는다.
- 한 앱에는 한 번에 한 모드만 붙는다. 붙어 있는 동안 다른 모드를 부르면 `Profiler already started` 로 실패한다.
- async-profiler 판은 `v4.5` 로 고정한다. 새 판은 나온 지 2주가 지난 뒤에 올린다.

만난 함정:

- 프로파일러의 `--include` 로 거르지 않는다. 필터의 `*` 는 앞이나 끝에만 쓸 수 있어 패키지로 고르지 못하고, 요청과 작업과 워커를 한 필터로 고를 수도 없다. 고르는 일은 모두 다듬는 단계가 한다.
- `jfrconv` 의 collapsed 는 프레임 이름 끝에 프레임 종류 표시(`_[j]`, `_[i]`)를 붙인다. 같은 메서드가 다른 이름으로 갈리지 않게 다듬는 단계가 뗀다.
- `jfrconv` 는 컨테이너 안의 자바로 돌아 `JDK_JAVA_OPTIONS` 를 읽는다. 트레이싱을 켠 동안에는 에이전트까지 붙어 느리게 뜨고 로그가 섞여, 비우고 돌린다.
- 그라파나의 노드 상한(16,384)을 넘으면 나머지가 `other` 로 뭉친다. 우리 코드의 스택만 보내면 회차 하나가 수백 노드라 넘지 않는다.
- wall 은 모든 스레드를 돌아가며 몇 개씩 뜬다. 톰캣 스레드가 200개 넘게 놀고 있으면 요청이 적을 때 표본이 적다. 부하가 걸린 회차 내내(`--profile`) 모은다.
