# 부하 시험

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
| `lib/` | 여러 도메인이 함께 쓰는 것. `token.js` 가 회원과 관리자의 Access Token 을 `JWT_SECRET` 으로 서명한다. `common.sh` 는 실행 스크립트가 불러 쓰는 공통 함수다 |

지금 있는 도메인은 `adserving/` 하나다.

## 공통 규칙

- 앱은 `loadtest` 프로필로 띄운다. 결제가 토스 대신 목을 쓴다.
- 생성기의 SQL 은 부하 시험 DB 와 로컬에만 넣는다. 정해진 번호 대역의 행과 그날 배치 기록을 지우고 다시 넣는다.
- 버전을 비교하려면 측정한 커밋과 조건을 함께 남긴다. 결과는 측정 조건과 같은 자리인 `docs/design/{도메인}/loadtest/` 에 둔다. 파일을 몇 개로 나눌지는 그 도메인이 정한다.

## 실행 스크립트의 공통 부분

`lib/common.sh` 가 어느 도메인이든 같은 일을 갖는다. 도메인의 `run.sh` 는 데이터 넣기와 시나리오처럼 그 도메인만의 일을 갖고, 공통 부분은 이 파일을 불러 쓴다.

| 함수 | 하는 일 |
|---|---|
| `remote`, `instance`, `instances` | 역할로 서버를 찾아 SSM 으로 스크립트를 보내고 끝날 때까지 기다린다 |
| `loadgen` | 부하 생성기에서 스크립트를 돌린다. 스크립트는 `BASE_URL`, `JWT`, `OUT` 을 쓴다 |
| `metrics` | 회차 구간의 앱, DB, 캐시, 부하 생성기 지표를 모은다 |
| `record_warnings` | 회차 구간의 경고와 오류를 이벤트별로 세어 결과 문서에 적고 커밋한다 |
| `profile` | 앱 서버에 프로파일링을 붙여 Pyroscope 로 보낸다 |
| `full_sha`, `image_exists` | 이미지 커밋을 40자로 늘리고 GHCR 에 있는지 본다 |
| `tracing_override`, `local_switch` | 앱에 OpenTelemetry 에이전트를 붙이는 compose 덮어쓰기 파일을 만들고, 로컬 앱을 그것으로 다시 띄운다 |

새 도메인은 `run.sh` 의 앞에서 아래 값을 정하고 이 파일을 불러 쓴다. `loadtest/adserving/run.sh` 가 예다.

```bash
LOCAL=""
[ "${1:-}" = --local ] && { LOCAL=1; shift; }
DOMAIN_EVENT_PREFIX=COUPON_                                       # 그 도메인 로그의 event 접두사
RESULT_DOC=docs/design/coupon/loadtest/쿠폰_부하_시험_결과.md       # 경고 로그를 적을 결과 문서
DOMAIN_METRICS='
issued_inc sum(increase(coupon_issued_total[$W]))
'                                                                 # metrics 가 더 묻는 지표(예). 없으면 비운다
. "$(dirname "$0")/../lib/common.sh"
```

- 회차를 돌린 뒤 `record_warnings <회차 이름> <시작 ms> <끝 ms>` 를 부르면 경고 로그가 남는다. 고르는 조건은 그라파나의 경고 로그 패널(lunch-catch/lc-infra#63)과 같다.
- 결과 문서에 제목에 `경고 로그` 가 든 절을 먼저 둔다. 절이 없으면 적지 않고 안내만 한다. 표는 처음 적을 때 만든다.
- 커밋은 결과 문서 하나만 지금 브랜치에 한다. 다른 파일이 스테이징돼 있으면 커밋하지 않는다. 시험용 브랜치에서 돌린다.

## 로컬에서 돌리기

lc-infra 의 로컬 구성(`observability/local`)에 앱과 관측 스택을 띄우고 부하 시험 환경과 같은 절차를 돌린다. 수치는 노트북의 것이라 결과 문서에 적지 않는다. 동작과 대시보드를 확인하는 데 쓴다.

```bash
# 1. 로컬 구성을 띄운다(lc-infra 의 docs/wiki/팀원-도메인-모니터링.md)
./gradlew bootJar -x test
(cd <lc-infra>/observability/local && docker compose up -d --build)

# 2. 같은 인자로 돌린다. LC_INFRA_DIR 은 lc-infra 경로다. 서명 키를 그 .env 에서 읽는다
export LC_INFRA_DIR=<lc-infra>
loadtest/adserving/run.sh --local seed
loadtest/adserving/run.sh --local prepare
loadtest/adserving/run.sh --local run local-run1 30000001 20 1m
loadtest/adserving/run.sh --local metrics <window 시작 ms> <window 끝 ms>

# 3. 대시보드를 찍는다(lc-infra 에서)
./scripts/loadtest-snapshot.sh --local --label local-run1 --from <window 시작 ms> --to <window 끝 ms>

# 4. 내린다
(cd <lc-infra>/observability/local && docker compose down -v)
```

- 시나리오와 생성기는 지금 작업 트리의 것을 쓴다. k6 는 이 기계에서 돌고 지표를 로컬 Prometheus 로 보내 05 부하 시험 대시보드가 같은 화면으로 채워진다.
- `reset`, `rows` 처럼 AWS 에만 있는 일은 로컬에서 막는다. `switch` 는 커밋 없이 `[--tracing <이름>]` 만 받아 로컬 앱을 다시 띄운다.
- 초당 피드는 노트북에 맞춰 낮춘다. 20 이면 초당 요청이 약 200 이다.

## 프로파일링

느린 요청이 어느 코드에서 시간을 쓰는지 본다. async-profiler(`v4.5`)를 앱 컨테이너에 넣어 붙인다. 앱을 다시 띄우지 않는다.

```bash
# 회차 도중에 붙인다. 기본은 60초, wall 이다
loadtest/adserving/run.sh profile v1-run1-profile 60 wall

# 회차 내내 붙인다. 이름을 -profile 로 끝낸다
loadtest/adserving/run.sh run v1-run1-profile 30030001 100 5m --profile wall
```

| 모드 | 재는 것 | 간격 |
|---|---|---|
| `cpu` | 계산에 쓴 시간이다. 컨테이너 안에서는 `-e cpu` 가 막혀 `itimer` 로 뜬다 | 10ms. async-profiler 의 기본값이다 |
| `wall` | 기다림까지 포함한 경과 시간이다. p99 를 줄이려면 이것을 먼저 본다 | 50ms. wall 모드의 기본값이다 |

**그라파나에서 보는 법:** Drilldown -> Profiles 에서 서비스 `lunchcatch` 를 고르고, 프로파일 종류(`process_cpu` 나 `wall`)와 `run` 라벨로 그 회차를 고른다. 10초씩 끊어 보내 대시보드와 함께 10초 남짓 늦게 보인다. Split 과 Single 로 비교와 단독을, Top Table 과 Flame Graph 로 표와 그래프를 바꾼다.

**읽는 법:** Self 는 그 메서드 자기 코드의 시간이고 Total 은 부른 메서드까지 합친 시간이다. 시간은 그 구간의 합계라 호출 한 번의 시간은 나오지 않는다.

**보내기 전에 다듬는다.** 이 서비스의 컨트롤러를 지나는 스택만 남기고 그래프가 컨트롤러(`FeedController.getFeed`)에서 시작하게 자른다. 이름은 패키지를 빼고, 호출마다 되풀이되는 스프링 프록시와 AOP 프레임은 뺀다. 다듬지 않으면 컨트롤러까지 프레임이 100개 넘게 쌓여 이름이 잘린다.

지킬 것:

- 측정 회차와 나눈다. 붙인 동안은 표본을 뜨는 비용이 든다. 프로파일링 회차는 결과 표와 경고 로그 기록에 넣지 않는다.
- 한 앱에는 한 번에 한 모드만 붙는다. 붙어 있는 동안 다른 모드를 부르면 `Profiler already started` 로 실패한다.
- async-profiler 판은 `v4.5` 로 고정한다. 새 판은 나온 지 2주가 지난 뒤에 올린다.

만난 함정:

- `--include` 필터는 JFR 출력에는 먹지 않는다. 그래서 collapsed 로 뜬다.
- 필터의 `*` 는 앞이나 끝에만 쓸 수 있다. 패키지로 고르지 못하고 `*Controller.*` 로 고른 뒤 다듬는 단계가 이 서비스의 패키지로 다시 고른다. 패키지를 빼는 `--simple` 을 쓰면 netty 와 JDK 의 `*Controller` 도 걸린다.
- 그라파나의 노드 상한(16,384)을 넘으면 나머지가 `other` 로 뭉친다. 컨트롤러를 지나는 스택만 보내면 회차 하나가 수백 노드라 넘지 않는다.
- wall 은 모든 스레드를 돌아가며 몇 개씩 뜬다. 톰캣 스레드가 200개 넘게 놀고 있으면 요청이 적을 때 표본이 적다. 부하가 걸린 회차 내내(`--profile`) 모은다.

## 추적

느린 요청 하나가 어느 단계에서 몇 ms 를 썼는지 본다. 앱에 OpenTelemetry Java 에이전트(`v2.32.0`)를 붙인다. 앱 코드는 바꾸지 않는다.

```bash
# 에이전트를 붙여 다시 띄운다. 이름이 추적의 run 라벨이 된다
loadtest/adserving/run.sh switch <이미지> --tracing v1-tracing
loadtest/adserving/run.sh run v1-run1-tracing 30030001 100 5m

# 떼려면 --tracing 없이 다시 띄운다
loadtest/adserving/run.sh switch <이미지>
```

- 앱 서버의 `/opt/lunchcatch-lt` 에 에이전트와 `compose.override.yaml` 을 둔다. compose 가 그 파일을 함께 읽어 lc-infra 의 설정을 고치지 않는다. `--tracing` 없이 띄우면 그 파일을 지운다. 로컬은 같은 파일을 `build/otel/` 에 만든다.
- 앱은 같은 compose 의 Alloy(`http://alloy:4318`)로 스팬을 보낸다. 남길 추적은 Alloy 의 테일 샘플링이 고른다. 오류와 300ms 넘는 요청은 모두, 피드와 노출은 10%, 나머지는 1% 남긴다(lunch-catch/lc-infra#72).
- 잴 메서드는 [`lib/otel-methods.txt`](lib/otel-methods.txt) 가 갖는다. 한 줄에 `패키지.클래스[메서드,메서드]` 이고 인터페이스가 아니라 실제 구현 클래스를 적는다. 도메인이 자기 메서드를 더한다.
- 라벨은 JVM 이 뜰 때 정해진다. 한 번 띄운 뒤의 회차는 같은 라벨이라 회차는 시각 구간으로 가린다.

**그라파나에서 보는 법:** Explore 에서 데이터 소스 `Tempo` 를 고르고 `{ resource.run = "v1-tracing" && name = "GET /v1/feed" && duration > 300ms }` 처럼 찾는다. 추적을 열면 메서드별 스팬이 시간 순으로 보인다. 스팬에서 그 요청의 로그(Loki)와 프로파일(Pyroscope)로 넘어간다. 로그의 `trace_id` 에서도 그 추적으로 넘어간다.

지킬 것:

- 측정 회차와 나눈다. 스팬을 만드는 비용이 p99 에 섞인다. 켠 회차는 이름을 `-tracing` 으로 끝내고 결과 표와 경고 로그 기록에 넣지 않는다.
- 노출 저장과 서빙 로그 대기열은 응답 뒤에 돌아 추적이 거기서 끊긴다.
- 에이전트 판은 `v2.32.0` 으로 고정한다. 새 판은 나온 지 2주가 지난 뒤에 올린다.
- 켠 동안은 Valkey 명령 지연 지표(`lettuce_seconds_*`)가 없어 04 데이터 저장소 대시보드의 `Valkey 명령 지연` 패널이 빈다. 스프링 부트의 Lettuce 관측이 에이전트의 Lettuce 계측을 덮어 Valkey 명령이 스팬이 되지 않아, 켠 동안만 그 자동 설정(`LettuceObservationAutoConfiguration`)을 뺀다. 그 시간은 추적에서 명령마다 보인다.
- 로그와 추적은 로그의 `trace_id` 로 잇는다. 요청 로그의 `traceId` 는 앱이 만든 별개의 값이라 추적과 이어지지 않는다.

