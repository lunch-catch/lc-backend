#!/usr/bin/env bash
#
# lc-infra 의 member-env.sh 로 띄운 부하 시험 환경에서 광고 서빙 시험을 돌린다(#997).
# 인스턴스에 SSM Run Command 로 스크립트를 보내고 끝날 때까지 기다려 출력을 보인다.
#
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh seed [회원 수]          시험 데이터를 RDS 에 넣는다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh prepare                 그날 00:00 묶음을 다시 돌린다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh run <이름> <회원 시작 번호> [초당 피드] [유지 시간] [--scenario feed] [--profile cpu|wall|both]
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh warmup <이름> <회원 시작 번호> [초당 피드] [--scenario feed]  램프업 뒤 5분을 데우고 끝 3분의 CPU 흔들림을 본다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh metrics <시작 ms> <끝 ms>  그 구간의 서버 지표
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh rows                    시험 데이터의 행 수
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh drain <이름> [건수]       대기 노출을 넣고 릴레이가 다 비우는 시간과 그 구간의 지표를 잰다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh clean                   drain 회차 사이에 노출, 판정, 원장 DEDUCT 를 비운다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh switch <sha> [--tracing <이름>] [--no-pool] [--env|--app-env|--batch-env KEY=VALUE]...  앱과 배치를 그 커밋 이미지로 다시 띄운다. GHCR 에 있어야 한다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh reset [회원 수]         Valkey 를 비우고 시험 데이터를 다시 넣는다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh profile <이름> [초] [cpu|wall|both]  앱 서버에 프로파일링을 붙인다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh version <버전> --sha <sha> [--from measure|diag] [--scenario feed] [--no-pool] [--env|--app-env|--batch-env KEY=VALUE]... [--rate 초당 피드] [--hold 유지 시간]
#                      버전 하나를 앱 대수 고정, switch, reset, prepare, 워밍업, 측정 3회까지 돌고 멈춘다. --from diag 는 진단 회차를 돈다(#1306)
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh plan <계획 파일> [--diag|--skip-diag]  측정 계획의 다음 걸음 하나를 간다(#1307). 계획은 loadtest/adserving/plans/ 에 있다
#
# run 의 끝에 --profile cpu|wall|both 를 주면 회차 내내 프로파일링을 붙인다. both 는 cpu 와 wall 을 한 기록(JFR)으로 함께 뜬다. 회차가 끝나면 경고 로그 건수를 결과 문서에 적고 커밋한다.
# run 의 --scenario feed 는 노출을 보내지 않고 피드만 보낸다(feed.js). 주지 않으면 피드와 노출을 보낸다(feed_impression.js).
# 피드만 보낸 회차는 경고 로그 건수를 서빙 결과 문서에 적는다.
# switch 에 --tracing <이름> 을 주면 OpenTelemetry 에이전트를 붙여 띄우고, 주지 않으면 뗀다. 이름은 트레이싱의 run 라벨이 된다.
# switch 에 --no-pool 을 주면 Valkey 연결 풀을 끄고 띄운다. 주지 않으면 켠다.
# switch 에 --env KEY=VALUE 를 주면 그 환경변수를 넣어 띄운다. 여러 번 줄 수 있다. 다시 빌드하지 않고 조정 값(예: ADSERVING_IMPRESSION_WRITEBUFFER_BATCHSIZE=1000)을 바꿔 잰다(#1170).
# --env 는 앱과 배치에 같게, --app-env 와 --batch-env 는 그 역할에만 넣는다(#1244).
# 첫 인자를 --local 로 주면 lc-infra 의 로컬 구성을 대상으로 seed, reset, prepare, run, metrics, profile 을 같은 인자로 돌린다.
# 로컬의 switch 는 커밋 없이 [--tracing <이름>] [--no-pool] [--env|--app-env|--batch-env KEY=VALUE]... 만 받아 로컬 앱과 배치를 다시 띄운다. 배치는 lc-infra 의 compose.lt-like.yaml 로 따로 뜬다.
# drain 은 노출 API 를 부르지 않는다. 회차마다 같은 수의 대기 노출만 비우므로 밀림이 다음 회차로 넘어가지 않는다(#1198).
# drain 은 노출이 남아 있으면 멈춘다. 회차 사이에 clean 을 부른다(#1283).
# 시나리오와 시드 생성기는 떠 있는 앱과 같은 커밋(SSM current-sha)에서 받는다. 값이 없으면 develop 이다.
# LOADTEST_REF 로 브랜치나 커밋을 따로 고를 수 있다.
set -euo pipefail

LOCAL=""
[ "${1:-}" = --local ] && { LOCAL=1; shift; }
DOMAIN_EVENT_PREFIX=ADSERVING_
# 회차가 끝나면 lc-infra 의 캡처가 대시보드의 도메인 폴더에서 광고 서빙만 찍는다(#1305)
DOMAIN_SNAPSHOT=adserving
#
# 기준 초과 표의 API 와 백그라운드 작업(워커, 릴레이)을 Call Tree 의 진입점 함수로 잇는다(#1365). 진단 회차의 캡처가 넘은 것을 펼친다.
# cron 작업은 이름이 클래스.메서드() 라 적지 않는다. 메서드 이름이 바뀌면 여기를 고친다. 맞지 않으면 캡처가 어느 진입점에도 없다고 출력한다
#
CALLTREE_ENTRIES='GET /v1/feed=FeedController.getFeed
POST /v1/impressions=ImpressionController.record
adserving.impression=ImpressionDispatchJob.dispatchPending
adserving.impression-write=ImpressionWriteBuffer.runWorker
adserving.serve-log-write=ServeLogBuffer.runWorker'
# version 이 쓰는 값이다(#1306). 그날 00:00 묶음을 다시 돌린다
PREPARE_STEP=1
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
# 결과 문서의 상세 표에 적는 광고 서빙 지표의 뜻이다. 꼴은 loadtest/lib/common.sh 의 COMMON_METRIC_NOTES 와 같다
DOMAIN_METRIC_NOTES='
served_cards_inc 피드로 내보낸 카드 수
serve_log_failed_inc 서빙 로그를 쓰다 실패해 버린 행 수
serve_log_dropped_inc 서빙 로그 대기열이 차 버린 카드 수
impression_dispatch_backlog_max 정산에 넘기지 않은 노출 수의 최대
impression_queue_max 노출 저장 대기열 길이의 최대(앱 서버 합)
impression_dropped_inc 노출 저장 대기열이 차 버린 노출 수
impression_write_failed_inc 노출 묶음 쓰기가 실패한 횟수(다시 쓴다)
impression_write_lost_inc 다시 써도 실패해 버린 노출 수
relay_deduct_avg_s 릴레이가 정산 차감 한 번에 걸린 평균 시간(초)
relay_deduct_rows_per_s 릴레이가 초당 정산에 넘긴 노출 수
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

# 버전 폴더는 결과 문서 옆에 시험 이름을 붙여 둔다(부하 시험 문서 8장). 서빙 버전은 이름이 serving- 으로 시작해 그대로 쓴다
version_dir() {
  case "$1" in
    serving-*) echo "$(dirname "$SERVING_RESULT_DOC")/$1" ;;
    *) echo "$(dirname "$RESULT_DOC")/impression-$1" ;;
  esac
}

#
# version 의 회원 대역이다(#1306). 대역은 부하 시험 문서 5장이 갖는다. 진단 회차는 결과에 적지 않아 워밍업 대역을 다시 쓴다.
# 서빙(피드만) 시험은 같은 표를 4만 뒤로, 서빙의 풀 끔은 한 번 더 4만 뒤로 옮겨 쓴다. 노출 시험은 풀을 켠 조건으로만 잰다.
#
version_bands() {
  local scenario="$1" nopool="$2"
  case "$scenario:$nopool" in
    :) WARMUP_BASE=30030001; RUN_BASES=(30000001 30010001 30020001) ;;
    feed:) WARMUP_BASE=30070001; RUN_BASES=(30040001 30050001 30060001) ;;
    feed:1) WARMUP_BASE=30110001; RUN_BASES=(30080001 30090001 30100001) ;;
    *) die "노출 시험은 풀을 끄고 재지 않는다. 풀 끔은 --scenario feed 와 함께 준다" ;;
  esac
  DIAG_BASE=$WARMUP_BASE
}

case "${LOCAL:+local-}${1:-}" in
  plan|local-plan)
    # 측정 계획의 다음 걸음 하나를 간다. 상태와 멈춤은 공통의 run_plan 이 갖는다(#1307)
    shift
    run_plan "$0" "${1:?계획 파일을 준다(예: loadtest/adserving/plans/1088-remeasure.plan)}" "${@:2}"
    ;;
  version|local-version)
    # 버전 하나를 측정 3회와 진단까지 돈다. 순서와 멈춤은 공통의 run_version 이 갖는다(#1306)
    shift
    run_version "$0" "${1:?버전 이름을 준다(예: v1)}" "${@:2}"
    ;;
  local-rows|local-drain|local-clean)
    die "로컬에서는 seed, reset, prepare, run, warmup, metrics, profile, switch 만 쓴다" ;;
  local-reset)
    # AWS 의 reset 과 같다. 회차를 이어 돌리면 기록 표가 쌓여 뒤 회차가 부풀어, 로컬도 같은 순서로 재려고 둔다(#1265)
    members="${2:-120000}"
    reset_state
    seed
    ;;
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
PROJECT=$PROJECT
$READY_SH
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
    # 버전 사이에 앞 버전이 쌓은 것을 지운다(부하 시험 문서 5장). 데이터베이스와 캐시를 비운 뒤 시드를 넣는다
    members="${2:-120000}"
    reset_state
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
      # 보낸 묶음의 원래 구간을 회차 폴더에 모은다(#1343). 캡처의 프로파일 보관이 읽는다
      PROFILE_BATCHES=""
      if [ -n "${SNAPSHOT_OUT:-}" ]; then
        mkdir -p "$SNAPSHOT_OUT/$name"
        PROFILE_BATCHES="$SNAPSHOT_OUT/$name/profile-batches.txt"
        : > "$PROFILE_BATCHES"
      fi
      export PROFILE_BATCHES
      profile "$name" $(( 60 + hold_ms / 1000 + 10 )) "$prof" &
      prof_pid=$!
    fi
    require_snapshot "$name"
    # 경고 로그를 셀 구간이다. k6 가 중간에 멈추거나 원격 명령이 실패해도 그때까지를 센다
    start=$(now_ms); rc=0; out=$(mktemp)
    ( loadgen 1800 "ulimit -n 250000 2> /dev/null || true
# 옛 커밋에는 피드만 보내는 시나리오가 없다. 없으면 피드를 보내지 않고 성공처럼 끝나므로 여기서 멈춘다
[ -f loadtest/adserving/$scenario.js ] || { echo \"scenario-missing loadtest/adserving/$scenario.js 가 이 ref 에 없다. LOADTEST_REF 로 #1127 뒤의 ref 를 준다\"; exit 2; }
start=\$(ms)
# 원격의 OUT 은 회차 사이에 남는다. 같은 이름으로 다시 돌다 k6 가 요약을 쓰기 전에 멈추면 앞 회차의 요약을 읽게 되어 먼저 지운다(#1329)
rm -f \$OUT/summary-$name.json
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
# 유지 구간의 실패를 결과 문서에 적으려고 k6 요약을 한 줄로 남긴다(#1329). 화면에는 내지 않는다
echo \"k6-summary \$(tr -d '\\n' < \$OUT/summary-$name.json 2> /dev/null || true)\"
grep -E '✓|✗|↳|impression_results|http_reqs|http_req_duration|http_req_failed|iterations|vus_max' \$OUT/k6-$name.log | head -40 || true
# 원격 쓰기 같은 k6 의 오류가 묻히지 않게 따로 보인다. 요약을 밀어내지 않게 마지막 몇 줄만 본다(lc-infra#60)
echo \"errors\"
grep 'level=error' \$OUT/k6-$name.log | tail -5 || true" ) > "$out" || rc=$?
    grep -v '^k6-summary ' "$out" || true
    #
    # 시나리오가 없어 k6 를 띄우지 않은 회차는 적을 것이 없다. 원격은 remote 가 실패를 종료 코드 1 로 바꿔
    # 종료 코드로 가를 수 없어 출력의 표시로 가른다.
    #
    if grep -q '^scenario-missing ' "$out"; then
      rc=2
    else
      record_warnings "$name" "$start" "$(now_ms)"
    fi
    # 프로파일링은 회차가 끝난 뒤 JFR 을 변환해 보낸다(#1345). 그것이 끝나야 캡처의 Call Tree 와 프로파일 보관이 모든 묶음을 본다.
    # 실패하면 캡처는 그대로 남기되 5 로 끝난다. 묶음이 빠진 진단을 끝난 것으로 적지 않아 plan --diag 로 다시 돌게 한다
    prc=0
    [ -z "${prof_pid:-}" ] || wait "$prof_pid" || prc=$?
    wait
    # 회차의 지표, 캡처, 무효 판정을 잇는다. 무효면 3, 캡처가 실패하면 4 로 끝나 다음 회차로 넘어가지 않는다(#1305)
    if [ "$rc" -ne 2 ]; then
      # 프로파일링이 실패했으면 캡처만 남기고 결과 문서와 커밋은 건너뛰게 알린다
      arc=0; PROFILE_FAILED="$([ "$prc" -eq 0 ] || echo 1)" after_run "$name" "$out" || arc=$?
      [ "$rc" -ne 0 ] || rc=$arc
    fi
    if [ "$prc" -ne 0 ] && [ "$rc" -eq 0 ]; then
      echo "PROFILE-FAILED $name: 프로파일링이 $prc 로 끝났다. 빠진 묶음이 있을 수 있어 진단을 끝난 것으로 적지 않는다" >&2
      rc=5
    fi
    exit "$rc"
    ;;
  warmup|local-warmup)
    # 워밍업이다(#1267). 판정은 공통 함수 warmup 이 갖고, 여기서는 광고 서빙의 run 에 넘길 인자만 정한다(#1270)
    # 회차를 끊어 되풀이하지 않는다. 같은 회원 대역을 되풀이하면 노출 상한에 닿아 피드가 새 분기를 타고 JIT 이 다시 오른다
    name="${2:?회차 이름을 준다}"; base="${3:?회원 시작 번호를 준다}"; rate=100
    shift 3
    [ $# -eq 0 ] || [ "${1#--}" != "$1" ] || { rate="$1"; shift; }
    warmup "$name" "$0" ${LOCAL:+--local} run "$name" "$base" "$rate" "${WARMUP_HOLD}s" "$@"
    ;;
  metrics|local-metrics)
    metrics "${2:-}" "${3:-}"
    ;;
  profile|local-profile)
    profile "${2:-}" "${3:-60}" "${4:-wall}"
    ;;
  clean)
    #
    # drain 회차 사이에 앞 회차가 남긴 노출, 판정, 원장 DEDUCT 를 비운다(#1283). 비우지 않고 이어 재면 커지는 원장에 DEDUCT 를 쓰는 비용이 회차마다 늘어 조건의 효과를 가를 수 없다.
    # 대량 DELETE 는 쌓인 환경에서 15분 가까이 걸려(#1235) 노출과 판정은 TRUNCATE 한다.
    # 원장은 그날 예약이 있어야 차감이 돌아 DEDUCT 가 아닌 행만 옮겨 두고 TRUNCATE 한 뒤 되돌린다. 표를 맞바꾸면 FK 가 따라오지 않아 그 표를 그대로 비운다.
    # 생성 컬럼에는 값을 넣을 수 없어 그 컬럼을 뺀 목록으로 옮긴다. 옮겨 둔 표는 다 되돌린 뒤에만 지워, 중간에 끊겨도 남는다.
    # 다 비우고 나면 dirty 페이지와 undo 가 가라앉을 때까지 기다린다. 비운 일의 뒷정리가 다음 회차와 겹치지 않게 한다.
    #
    remote "$(instance app)" 1200 "set -euo pipefail
$DB_ENV
[ \"\$(mysql -N -e \"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'point_ledger_keep'\")\" -eq 0 ] \\
  || { echo '앞의 clean 이 남긴 point_ledger_keep 이 있다. 원장에 되돌렸는지 보고 지운 뒤 다시 부른다' >&2; exit 1; }
keep=\$(mysql -N -e \"SELECT COUNT(*) FROM point_ledger WHERE entry_type <> 'DEDUCT'\")
mysql -e \"TRUNCATE TABLE impression_log;
TRUNCATE TABLE deduction_result;
SET SESSION group_concat_max_len = 65535;
SET @cols = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'point_ledger' AND GENERATION_EXPRESSION = '');
SET @s = CONCAT('CREATE TABLE point_ledger_keep AS SELECT ', @cols, ' FROM point_ledger WHERE entry_type <> ''DEDUCT''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
TRUNCATE TABLE point_ledger;
SET @s = CONCAT('INSERT INTO point_ledger (', @cols, ') SELECT ', @cols, ' FROM point_ledger_keep');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;\"
left=\$(mysql -N -e \"SELECT (SELECT COUNT(*) FROM impression_log), (SELECT COUNT(*) FROM deduction_result),
  (SELECT COUNT(*) FROM point_ledger WHERE entry_type = 'DEDUCT'), (SELECT COUNT(*) FROM point_ledger)\")
echo \"impression deduction ledger_deduct ledger: \$left\"
[ \"\$(echo \$left | awk '{print \$1 + \$2 + \$3}')\" -eq 0 ] && [ \"\$(echo \$left | awk '{print \$4}')\" -eq \"\$keep\" ] \\
  || { echo \"비우지 못했다. 원장에 되돌릴 행 \$keep 건은 point_ledger_keep 에 남아 있다\" >&2; exit 1; }
mysql -e 'DROP TABLE point_ledger_keep'
for i in \$(seq 1 90); do
  # 부하 시험 RDS 는 performance_schema 가 꺼져 있어 dirty 페이지는 SHOW 로 읽는다
  state=\"\$(mysql -N -e \"SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_pages_dirty'\" | awk '{print \$2}') \$(mysql -N -e \"SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = 'trx_rseg_history_len'\")\"
  echo \"dirty history: \$state\"
  [ \"\$(echo \$state | awk '{print (\$1 < 200 && \$2 < 1000)}')\" -eq 1 ] && exit 0
  sleep 10
done
echo '15분 안에 가라앉지 않았다' >&2; exit 1"
    ;;
  drain)
    #
    # 노출 API 부하 없이 릴레이(ImpressionDispatchJob)가 대기 노출을 비우는 속도를 잰다(#1198).
    # 앱 서버에서 그날 유효 노출 N건을 한 트랜잭션으로 넣고, 넣은 뒤부터 남은 노출이 0 이 될 때까지를 한 구간으로 본다.
    # 노출은 시드의 캠페인 1,000개와 회원 5,000명에 고르게 나눈다. 캠페인 하루 예산이 커서 회차마다 같은 비율로 과금된다.
    # 노출이 한 건이라도 남아 있으면 넣지 않고 멈춘다. 앞 회차가 남긴 판정과 원장 DEDUCT 위에서 재면 회차마다 느려져, 회차 사이에 clean 으로 비운다(#1283).
    # 도는 동안에는 넘기지 않은 노출을 idx_imp_dispatch 만으로 센다. 포기한 노출을 거르려고 행 본문을 읽으면 그 읽기가 차감과 버퍼 풀을 나눠 써 잴 대상을 늦춘다.
    # 그 수가 줄지 않을 때만 포기한 노출을 뺀 수를 다시 센다. 남은 것이 모두 포기한 노출이면 끝난 것이다.
    #
    name="${2:?회차 이름을 준다}"; count="${3:-50000}"
    case "$count" in ''|*[!0-9]*) die "건수는 양의 정수로 준다" ;; esac
    [ "$count" -gt 0 ] || die "건수는 양의 정수로 준다"
    out=$(remote "$(instance app)" 3900 "set -euo pipefail
$DB_ENV
PENDING=\"SELECT COUNT(*) FROM impression_log WHERE dispatched_at IS NULL AND dispatch_abandoned_at IS NULL AND created_at >= TIMESTAMP(DATE_SUB('\$D', INTERVAL 1 DAY))\"
UNSENT=\"SELECT COUNT(*) FROM impression_log WHERE dispatched_at IS NULL AND created_at >= TIMESTAMP(DATE_SUB('\$D', INTERVAL 1 DAY))\"
[ \"\$(mysql -N -e 'SELECT EXISTS (SELECT 1 FROM impression_log)')\" -eq 0 ] || { echo '앞 회차의 노출이 남아 있다. run.sh clean 으로 비운 뒤 다시 부른다' >&2; exit 1; }
mysql -e \"SET SESSION time_zone = '+09:00';
SET SESSION cte_max_recursion_depth = $count;
INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type, slot_position, displayed_at,
                            lat_rounded, lng_rounded, validity, invalid_reason, sold_out, viewed_ms, created_at, updated_at)
WITH RECURSIVE n (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i + 1 < $count)
SELECT UUID_TO_BIN(UUID(), 1), 30000001 + MOD(i, 5000), '\$D', 30000001 + MOD(i, 1000), 30000001 + MOD(i, 1000),
       'ALLOCATION', 1, NOW(6), 37.556, 126.922, 'VALID', NULL, FALSE, NULL, NOW(6), NOW(6)
FROM n;\"
echo \"start \$(date +%s%3N)\"
prev=-1
for i in \$(seq 1 720); do
  left=\$(mysql -N -e \"\$UNSENT\")
  [ \"\$left\" -eq 0 ] && { echo \"end \$(date +%s%3N)\"; exit 0; }
  if [ \"\$left\" -eq \"\$prev\" ]; then
    [ \"\$(mysql -N -e \"\$PENDING\")\" -eq 0 ] && { echo \"end \$(date +%s%3N)\"; exit 0; }
  fi
  prev=\$left
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
    sed -n '3,29p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
