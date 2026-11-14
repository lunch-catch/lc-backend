#!/usr/bin/env bash
#
# lc-infra 의 member-env.sh 로 띄운 부하 시험 환경에서 광고 서빙 시험을 돌린다(#997).
# 인스턴스에 SSM Run Command 로 스크립트를 보내고 끝날 때까지 기다려 출력을 보인다.
#
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh seed [회원 수]          시험 데이터를 RDS 에 넣는다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh prepare                 그날 00:00 묶음을 다시 돌린다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh run <이름> <회원 시작 번호> [초당 피드] [유지 시간] [--scenario feed] [--profile cpu|wall|both]
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh metrics <시작 ms> <끝 ms>  그 구간의 서버 지표
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh rows                    시험 데이터의 행 수
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh drain <이름> [건수]       대기 노출을 넣고 릴레이가 다 비우는 시간과 그 구간의 지표를 잰다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh switch <sha> [--tracing <이름>] [--no-pool] [--env|--app-env|--batch-env KEY=VALUE]...  앱과 배치를 그 커밋 이미지로 다시 띄운다. GHCR 에 있어야 한다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh reset [회원 수]         Valkey 를 비우고 시험 데이터를 다시 넣는다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh profile <이름> [초] [cpu|wall|both]  앱 서버에 프로파일링을 붙인다
#
# run 의 끝에 --profile cpu|wall|both 를 주면 회차 내내 프로파일링을 붙인다. both 는 cpu 와 wall 을 한 기록(JFR)으로 함께 뜬다. 회차가 끝나면 경고 로그 건수를 결과 문서에 적고 커밋한다.
# run 의 --scenario feed 는 노출을 보내지 않고 피드만 보낸다(feed.js). 주지 않으면 피드와 노출을 보낸다(feed_impression.js).
# 피드만 보낸 회차는 경고 로그 건수를 서빙 결과 문서에 적는다.
# switch 에 --tracing <이름> 을 주면 OpenTelemetry 에이전트를 붙여 띄우고, 주지 않으면 뗀다. 이름은 트레이싱의 run 라벨이 된다.
# switch 에 --no-pool 을 주면 Valkey 연결 풀을 끄고 띄운다. 주지 않으면 켠다.
# switch 에 --env KEY=VALUE 를 주면 그 환경변수를 넣어 띄운다. 여러 번 줄 수 있다. 다시 빌드하지 않고 조정 값(예: ADSERVING_IMPRESSION_WRITEBUFFER_BATCHSIZE=1000)을 바꿔 잰다(#1170).
# --env 는 앱과 배치에 같게, --app-env 와 --batch-env 는 그 역할에만 넣는다(#1244).
# 첫 인자를 --local 로 주면 lc-infra 의 로컬 구성을 대상으로 seed, prepare, run, metrics, profile 을 같은 인자로 돌린다.
# 로컬의 switch 는 커밋 없이 [--tracing <이름>] [--no-pool] [--env|--app-env|--batch-env KEY=VALUE]... 만 받아 로컬 앱과 배치를 다시 띄운다. 배치는 lc-infra 의 compose.lt-like.yaml 로 따로 뜬다.
# drain 은 노출 API 를 부르지 않는다. 회차마다 같은 수의 대기 노출만 비우므로 밀림이 다음 회차로 넘어가지 않는다(#1198).
# 시나리오와 시드 생성기는 떠 있는 앱과 같은 커밋(SSM current-sha)에서 받는다. 값이 없으면 develop 이다.
# LOADTEST_REF 로 브랜치나 커밋을 따로 고를 수 있다.
set -euo pipefail

LOCAL=""
[ "${1:-}" = --local ] && { LOCAL=1; shift; }
DOMAIN_EVENT_PREFIX=ADSERVING_
# 따로 준 결과 문서는 시나리오가 바꾸지 않는다
RESULT_DOC_GIVEN="${RESULT_DOC:-}"
RESULT_DOC="${RESULT_DOC:-docs/design/adserving/loadtest/노출_수집_부하_시험_결과.md}"
SERVING_RESULT_DOC="docs/design/adserving/loadtest/서빙_부하_시험_결과.md"
# 공통 지표 뒤에 묻는 광고 서빙의 지표다. 꼴은 loadtest/lib/common.sh 가 정한다
DOMAIN_METRICS='
served_cards_inc sum(increase(adserving_served_cards_total[$W]))
serve_log_failed_inc sum(increase(adserving_serve_log_failed_total[$W]))
serve_log_dropped_inc sum(increase(adserving_serve_log_dropped_total[$W]))
# 노출 수집 v2 부터의 릴레이와 v3 부터의 저장 큐다. 그 전 버전에는 지표가 없어 비어 나온다
impression_dispatch_backlog_max max_over_time(sum(adserving_impression_dispatch_backlog)[$W:5s])
impression_queue_max max_over_time(sum(adserving_impression_queue_size)[$W:5s])
impression_dropped_inc sum(increase(adserving_impression_dropped_total[$W]))
impression_write_failed_inc sum(increase(adserving_impression_write_failed_total[$W]))
impression_write_lost_inc sum(increase(adserving_impression_write_lost_total[$W]))
# 릴레이가 정산 한 번에 걸린 시간의 평균과 초당 넘긴 건수다. 릴레이 처리량의 상한을 가른다
relay_deduct_avg_s sum(increase(adserving_impression_dispatch_deduct_seconds_sum[$W]))/sum(increase(adserving_impression_dispatch_deduct_seconds_count[$W]))
relay_deduct_rows_per_s sum(rate(adserving_impression_dispatch_deduct_batch_sum[$W]))
'
. "$(dirname "$0")/../lib/common.sh"

#
# 시드는 앱 서버에서 돈다. DB 보안 그룹이 부하 생성기를 받지 않아서다(부하 시험 문서 3장).
# 앱 서버에는 저장소 사본이 없어 생성기만 raw 로 받는다. 경로가 $REF 에 박히므로
# 이 파일을 옮기면 그 커밋부터만 받힌다. 경로를 옮긴 커밋보다 앞선 ref 를 쓸 때는
# LOADTEST_REF 를 이 커밋 이후로 준다 (#1080).
#
#
# switch 의 선택지를 읽어 label, nopool, extra, app_extra, batch_extra 를 정한다. 셋 모두 KEY=VALUE 를 줄마다 하나씩 담는다.
# --env 는 앱과 배치에 같게, --app-env 와 --batch-env 는 그 역할에만 넣는다(#1244). 앱과 배치의 DB 시간 제한처럼 값이 다를 때 쓴다.
#
switch_options() {
  label=""; nopool=""; extra=""; app_extra=""; batch_extra=""
  local opt pair key
  while [ $# -gt 0 ]; do
    opt="$1"
    case "$opt" in
      --tracing) label="${2:?--tracing 뒤에 이름을 준다}"; shift 2; continue ;;
      --no-pool) nopool=1; shift; continue ;;
      --env|--app-env|--batch-env) ;;
      *) die "모르는 선택지다: $opt" ;;
    esac
    pair="${2:-}"; key="${pair%%=*}"
    [[ "$pair" =~ ^[A-Z_][A-Z0-9_]*= ]] || die "$opt 뒤에 KEY=VALUE 를 준다. KEY 는 대문자 환경변수 이름이다: $pair"
    # 줄바꿈은 값 하나를 여러 환경변수로 쪼개고, 다른 제어 문자는 compose 가 덮어쓰기 파일을 읽지 못하게 한다
    [[ "$pair" != *[[:cntrl:]]* ]] || die "$opt 의 VALUE 에 줄바꿈이나 제어 문자를 넣지 않는다: $key"
    case "$key" in
      JDK_JAVA_OPTIONS|OTEL_*|SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED)
        die "$opt 로 $key 를 주지 않는다. 그 키는 --tracing 과 --no-pool 이 쓴다" ;;
    esac
    # 한 역할에 같은 키가 둘이면 compose 가 덮어쓰기 파일을 읽지 못해, current-sha 를 바꾼 뒤 다시 띄우다 멈춘다
    case "$opt" in
      --env) [[ $'\n'"$extra$app_extra$batch_extra" != *$'\n'"$key="* ]] || die "$key 를 두 번 주었다" ;;
      --app-env) [[ $'\n'"$extra$app_extra" != *$'\n'"$key="* ]] || die "앱에 $key 를 두 번 주었다" ;;
      --batch-env) [[ $'\n'"$extra$batch_extra" != *$'\n'"$key="* ]] || die "배치에 $key 를 두 번 주었다" ;;
    esac
    case "$opt" in
      --env) extra="$extra$pair"$'\n' ;;
      --app-env) app_extra="$app_extra$pair"$'\n' ;;
      --batch-env) batch_extra="$batch_extra$pair"$'\n' ;;
    esac
    shift 2
  done
}

seed() {
  # 로컬은 지금 작업 트리의 생성기로 로컬 MySQL 에 넣는다
  if [ -n "$LOCAL" ]; then
    python3 "$ROOT/loadtest/adserving/gen_data.py" --date "$(TZ=Asia/Seoul date +%F)" --members "$members" --stores 1000 | local_mysql
    local_mysql -N -e "select 'member', count(*) from member where member_id >= 30000001;"
    return
  fi
  remote "$(instance app)" 900 "set -euo pipefail
$DB_ENV
rm -rf /tmp/lt && mkdir -p /tmp/lt && cd /tmp/lt
curl -fsSL https://raw.githubusercontent.com/lunch-catch/lc-backend/$REF/loadtest/adserving/gen_data.py -o gen.py
docker run --rm -v /tmp/lt:/w python:3.12-slim python /w/gen.py --date \"\$D\" --members $members --stores 1000 > seed.sql
mysql < seed.sql
mysql -N -e \"select 'member', count(*) from member where member_id >= 30000001;
select 'batch', status from batch_execution_log where business_date = '\$D' and job_name = 'DAILY_0000';\""
}

case "${LOCAL:+local-}${1:-}" in
  local-reset|local-rows|local-drain)
    die "로컬에서는 seed, prepare, run, metrics, profile, switch 만 쓴다" ;;
  local-switch)
    shift
    switch_options "$@"
    local_switch "$label" "$nopool" "$extra" "$app_extra" "$batch_extra"
    ;;
  seed|local-seed)
    members="${2:-120000}"
    seed
    ;;
  switch)
    sha="${2:?이미지 커밋을 준다}"
    shift 2
    switch_options "$@"
    sha=$(full_sha "$sha") || die "lc-backend 에 그 커밋이 없다"
    #
    # current-sha 를 바꾸고 전부 다시 띄우기 전에 이미지가 있는지 본다.
    # 없는 태그면 앱과 배치가 모두 이미지를 못 받아 죽고, 되돌릴 때까지 환경이 멈춘다.
    #
    rc=0
    image_exists "$sha" || rc=$?
    case "$rc" in
      0) ;;
      1) die "ghcr.io/$IMAGE_REPO:$sha 가 없다. develop 을 거친 커밋인지, Actions 의 '이미지 공개' 가 끝났는지 본다" ;;
      *) die "GHCR 에 이미지가 있는지 확인하지 못했다. 네트워크를 보고 다시 부른다" ;;
    esac
    aws ssm put-parameter --name "/$PROJECT/current-sha" --type String --overwrite --value "$sha" > /dev/null
    # 앱과 배치를 모두 새 이미지로 함께 다시 띄운다. 마이그레이션은 Flyway 가 먼저 뜬 쪽에서 한 번 돈다
    # 다시 뜨는 동안 헬스체크가 실패해 오토스케일링 그룹이 서버를 바꾸지 않게 교체를 멈춰 둔다
    aws autoscaling suspend-processes --auto-scaling-group-name "$PROJECT-app" --scaling-processes ReplaceUnhealthy
    #
    # 트레이싱을 켜거나 풀을 끄면 compose 덮어쓰기 파일을 두고, 둘 다 아니면 그 파일을 지운다.
    # compose 가 같은 디렉터리의 compose.override.yaml 을 함께 읽어 lc-infra 의 설정을 고치지 않는다.
    #
    # 배치 서버는 서비스 이름을 lunchcatch-batch 로 띄운다. Alloy 가 그 이름으로 배치의 작업 트레이스를 따로 고른다(#1199)
    # 앱 서버에는 --env 와 --app-env 를, 배치 서버에는 --env 와 --batch-env 를 넣는다(#1244). 넣을 것이 없는 쪽은 덮어쓰기를 지운다
    fetch=""
    # 받다 끊긴 jar 가 남아 다음에 그대로 쓰이지 않게 다 받은 뒤에만 제 이름으로 옮긴다
    [ -z "$label" ] || fetch="[ -s /opt/$PROJECT/otel-$OTEL_VERSION.jar ] || { curl -fsSL $OTEL_JAR_URL -o /opt/$PROJECT/otel-$OTEL_VERSION.jar.part && mv /opt/$PROJECT/otel-$OTEL_VERSION.jar.part /opt/$PROJECT/otel-$OTEL_VERSION.jar; }"
    app_tracing="rm -f /opt/$PROJECT/compose.override.yaml"
    batch_tracing="$app_tracing"
    if [ -n "$label$nopool$extra$app_extra" ]; then
      app_tracing="$fetch
cat > /opt/$PROJECT/compose.override.yaml <<'OVERRIDE'
$(app_override "$label" "./otel-$OTEL_VERSION.jar" "$nopool" "$extra$app_extra" lunchcatch)
OVERRIDE"
    fi
    if [ -n "$label$nopool$extra$batch_extra" ]; then
      batch_tracing="$fetch
cat > /opt/$PROJECT/compose.override.yaml <<'OVERRIDE'
$(app_override "$label" "./otel-$OTEL_VERSION.jar" "$nopool" "$extra$batch_extra" lunchcatch-batch)
OVERRIDE"
    fi
    pids=""; rc=0
    # 여러 대면 aws 가 탭으로 잇는다. echo 로 공백 하나씩으로 펴서 아래 case 가 id 를 찾게 한다
    batches=" $(echo $(instances batch)) "
    for id in $(instances app) $batches; do
      tracing="$app_tracing"
      case "$batches" in *" $id "*) tracing="$batch_tracing" ;; esac
      echo "restart $id"
      remote "$id" 600 "set -euo pipefail
$tracing
/usr/local/bin/$PROJECT-refresh-env
systemctl restart $PROJECT
for i in \$(seq 1 60); do
  curl -fs localhost:8081/actuator/health/readiness > /dev/null && break; sleep 5
done
docker inspect $PROJECT --format '{{.Config.Image}}'
docker inspect $PROJECT --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^(JDK_JAVA_OPTIONS|OTEL_SERVICE_NAME)=' || echo 'tracing off'
docker inspect $PROJECT --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED=' || echo 'pool on'
curl -fs localhost:8081/actuator/health/readiness" &
      pids="$pids $!"
    done
    for pid in $pids; do
      wait "$pid" || rc=1
    done
    aws autoscaling resume-processes --auto-scaling-group-name "$PROJECT-app" --scaling-processes ReplaceUnhealthy
    # 서버 하나라도 새 이미지나 추적 설정으로 뜨지 못했으면 교체를 다시 켠 뒤 실패로 끝난다
    [ "$rc" -eq 0 ] || die "다시 띄우지 못한 서버가 있다. 위 출력에서 그 서버를 확인한다"
    ;;
  reset)
    # 버전 사이에 앞 버전이 쌓은 것을 지운다(부하 시험 문서 5장). 시드가 시험 데이터와 그날 배치 기록을 지우고 다시 넣는다
    members="${2:-120000}"
    remote "$(instance app)" 300 "set -euo pipefail
CACHE=\$(docker inspect $PROJECT --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^VALKEY_HOST=' | cut -d= -f2-)
docker run --rm valkey/valkey:9.0-alpine valkey-cli -h \"\$CACHE\" FLUSHALL
docker run --rm valkey/valkey:9.0-alpine valkey-cli -h \"\$CACHE\" DBSIZE"
    seed
    ;;
  prepare|local-prepare)
    loadgen 1200 "k6 run -q -e BASE_URL=\"\$BASE_URL\" -e JWT_SECRET=\"\$JWT\" -e BUSINESS_DATE=\$(TZ=Asia/Seoul date +%F) -e WAIT_SECONDS=900 \
  loadtest/adserving/prepare.js 2>&1 | grep -E 'level=' || true"
    ;;
  run|local-run)
    name="${2:?회차 이름을 준다}"; base="${3:?회원 시작 번호를 준다}"; rate=100; hold=5m
    prof=""; scenario=feed_impression
    shift 3
    # 초당 피드와 유지 시간은 생략할 수 있어 -- 로 시작하는 선택지가 나오기 전까지만 위치 인자로 읽는다
    [ $# -eq 0 ] || [ "${1#--}" != "$1" ] || { rate="$1"; shift; }
    [ $# -eq 0 ] || [ "${1#--}" != "$1" ] || { hold="$1"; shift; }
    while [ $# -gt 0 ]; do
      case "$1" in
        --profile) prof="${2:?--profile 뒤에 cpu, wall, both 가운데 하나를 준다}"; shift 2 ;;
        --scenario) scenario="${2:?--scenario 뒤에 feed 를 준다}"; shift 2 ;;
        *) die "모르는 선택지다: $1" ;;
      esac
    done
    case "$scenario" in
      feed) [ -n "$RESULT_DOC_GIVEN" ] || RESULT_DOC="$SERVING_RESULT_DOC" ;;
      feed_impression) ;;
      *) die "시나리오는 feed 나 feed_impression 이다" ;;
    esac
    # 유지 시간은 k6 의 DURATION 그대로 넘기고, 유지 구간의 경계를 출력하려고 밀리초로도 바꾼다(#1092)
    hold_ms=$(( $(k6_seconds "$hold") * 1000 ))
    #
    # 프로파일링은 램프업과 유지 구간 내내 붙인다. 표본을 뜨는 비용이 들어 그 회차는 결과에 적지 않는다.
    # 프로파일링 회차라는 것이 이름에 남게 -profile 로 끝나는 이름을 받는다.
    #
    if [ -n "$prof" ]; then
      case "$name" in *-profile) ;; *) die "프로파일링 회차는 이름을 -profile 로 끝낸다. 예: v3c-run1-profile" ;; esac
      profile "$name" $(( 60 + hold_ms / 1000 + 10 )) "$prof" &
    fi
    # 경고 로그를 셀 구간이다. k6 가 중간에 멈추거나 원격 명령이 실패해도 그때까지를 센다
    start=$(now_ms); rc=0; out=$(mktemp)
    ( loadgen 1800 "ulimit -n 250000 2> /dev/null || true
# 옛 커밋에는 피드만 보내는 시나리오가 없다. 없으면 피드를 보내지 않고 성공처럼 끝나므로 여기서 멈춘다
[ -f loadtest/adserving/$scenario.js ] || { echo \"scenario-missing loadtest/adserving/$scenario.js 가 이 ref 에 없다. LOADTEST_REF 로 #1127 뒤의 ref 를 준다\"; exit 2; }
start=\$(ms)
k6 run -q -o experimental-prometheus-rw --tag testid=adserving-$name \\
  -e BASE_URL=\"\$BASE_URL\" -e JWT_SECRET=\"\$JWT\" -e FEED_RATE=$rate -e RAMP_UP_SECONDS=60 -e DURATION=$hold \\
  -e MEMBERS=10000 -e MEMBER_BASE=$base -e PRE_VUS=200 -e MAX_VUS=2000 \\
  --summary-export \$OUT/summary-$name.json loadtest/adserving/$scenario.js > \$OUT/k6-$name.log 2>&1 || true
echo \"window \$start \$(ms)\"
# 유지 구간의 경계는 k6 가 남긴 시나리오 시작 시각에 램프업 60초를 더해 낸다.
# 그 줄이 없으면 k6 를 띄우기 전 시각으로 대신한다. 그때는 k6 가 뜨는 몇 초만큼 실제보다 이르다
s0=\$(grep -m1 -o 'scenario_start [0-9]*' \$OUT/k6-$name.log | cut -d' ' -f2 || true)
s0=\${s0:-\$start}
echo \"hold \$((s0 + 60000)) \$((s0 + 60000 + $hold_ms))\"
grep -E '✓|✗|↳|impression_results|http_reqs|http_req_duration|http_req_failed|iterations|vus_max' \$OUT/k6-$name.log | head -40 || true
# 원격 쓰기 같은 k6 의 오류가 묻히지 않게 따로 보인다. 요약을 밀어내지 않게 마지막 몇 줄만 본다(lc-infra#60)
echo \"errors\"
grep 'level=error' \$OUT/k6-$name.log | tail -5 || true" ) > "$out" || rc=$?
    cat "$out"
    #
    # 시나리오가 없어 k6 를 띄우지 않은 회차는 적을 것이 없다. 원격은 remote 가 실패를 종료 코드 1 로 바꿔
    # 종료 코드로 가를 수 없어 출력의 표시로 가른다.
    #
    if grep -q '^scenario-missing ' "$out"; then
      rc=2
    else
      record_warnings "$name" "$start" "$(now_ms)"
    fi
    wait
    exit "$rc"
    ;;
  metrics|local-metrics)
    metrics "${2:-}" "${3:-}"
    ;;
  profile|local-profile)
    profile "${2:-}" "${3:-60}" "${4:-wall}"
    ;;
  drain)
    #
    # 노출 API 부하 없이 릴레이(ImpressionDispatchJob)가 대기 노출을 비우는 속도를 잰다(#1198).
    # 앱 서버에서 그날 유효 노출 N건을 한 트랜잭션으로 넣고, 넣은 뒤부터 남은 노출이 0 이 될 때까지를 한 구간으로 본다.
    # 노출은 시드의 캠페인 1,000개와 회원 5,000명에 고르게 나눈다. 캠페인 하루 예산이 커서 회차마다 같은 비율로 과금된다.
    # 앞 회차의 노출이 남아 있으면 구간이 섞이므로 넣지 않고 멈춘다.
    #
    name="${2:?회차 이름을 준다}"; count="${3:-50000}"
    case "$count" in ''|*[!0-9]*) die "건수는 양의 정수로 준다" ;; esac
    [ "$count" -gt 0 ] || die "건수는 양의 정수로 준다"
    out=$(remote "$(instance app)" 3900 "set -euo pipefail
$DB_ENV
PENDING=\"SELECT COUNT(*) FROM impression_log WHERE dispatched_at IS NULL AND dispatch_abandoned_at IS NULL AND created_at >= TIMESTAMP(DATE_SUB('\$D', INTERVAL 1 DAY))\"
left=\$(mysql -N -e \"\$PENDING\")
[ \"\$left\" -eq 0 ] || { echo \"앞 회차의 대기 노출 \$left 건이 남아 있다. 비워진 뒤 다시 부른다\" >&2; exit 1; }
mysql -e \"SET SESSION time_zone = '+09:00';
SET SESSION cte_max_recursion_depth = $count;
INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type, slot_position, displayed_at,
                            lat_rounded, lng_rounded, validity, invalid_reason, sold_out, viewed_ms, created_at, updated_at)
WITH RECURSIVE n (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i + 1 < $count)
SELECT UUID_TO_BIN(UUID(), 1), 30000001 + MOD(i, 5000), '\$D', 30000001 + MOD(i, 1000), 30000001 + MOD(i, 1000),
       'ALLOCATION', 1, NOW(6), 37.556, 126.922, 'VALID', NULL, FALSE, NULL, NOW(6), NOW(6)
FROM n;\"
echo \"start \$(date +%s%3N)\"
for i in \$(seq 1 720); do
  left=\$(mysql -N -e \"\$PENDING\")
  [ \"\$left\" -eq 0 ] && { echo \"end \$(date +%s%3N)\"; exit 0; }
  sleep 5
done
echo \"1시간 안에 비우지 못했다. 남은 노출 \$left 건\" >&2; exit 1")
    printf '%s\n' "$out"
    start=$(printf '%s\n' "$out" | awk '$1 == "start" {print $2}')
    end=$(printf '%s\n' "$out" | awk '$1 == "end" {print $2}')
    [ -n "$start" ] && [ -n "$end" ] || die "구간을 읽지 못했다"
    echo "drain $name $count 건, $(( (end - start) / 1000 ))초, 초당 $(( count * 1000 / (end - start) ))건"
    metrics "$start" "$end"
    ;;
  rows)
    remote "$(instance app)" 300 "set -euo pipefail
$DB_ENV
mysql -N -e \"select 'serve_log', count(*) from serve_log where member_id >= 30000001;
select 'impression', validity, count(*) from impression_log where member_id >= 30000001 group by validity;
select 'deduction', billing_status, count(*) from deduction_result where campaign_id >= 30000001 group by billing_status;
select 'ledger', entry_type, count(*) from point_ledger where owner_id >= 30000001 group by entry_type;\""
    ;;
  *)
    sed -n '3,27p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
