#!/usr/bin/env bash
#
# 도메인마다 같은 부하 시험 일을 모은다(#1094). 도메인의 run.sh 가 아래 값을 정하고 이 파일을 불러 쓴다.
# 쓰는 법은 loadtest/README.md 가 갖는다.
#
#   LOCAL                첫 인자가 --local 이면 1 이다. lc-infra 의 로컬 구성(observability/local)을 대상으로 돈다
#   DOMAIN_EVENT_PREFIX  그 도메인 로그의 event 접두사다. 예: ADSERVING_
#   RESULT_DOC           경고 로그 건수를 적을 결과 문서다. 저장소 루트에서의 경로다
#   DOMAIN_METRICS       metrics 가 공통 지표 뒤에 더 묻는 지표다. 한 줄에 "이름 PromQL" 이고 구간은 $W 로 쓴다
#

# lc-infra 의 member-env.sh 가 어느 계정에서나 이 접두사로 띄운다 (lunch-catch/lc-infra#53). 리소스, SSM 경로, 컨테이너 이름이 모두 이것이다
PROJECT="${PROJECT:-lunchcatch-lt}"
REGION="${AWS_REGION:-ap-northeast-2}"
REPO="https://github.com/lunch-catch/lc-backend.git"
IMAGE_REPO="lunch-catch/lc-backend"
export AWS_REGION="$REGION"
ROOT=$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)
# 로컬 구성의 compose 이름이다. 컨테이너가 lunchcatch-local-app-1 처럼 뜬다
LOCAL_PROJECT=lunchcatch-local
LC_INFRA_DIR="${LC_INFRA_DIR:-$ROOT/../lc-infra}"
# 2주가 지난 판을 고정한다. 4.2 부터 컨테이너 안에서 붙는다
ASPROF_VERSION=4.5
# OpenTelemetry Java 에이전트도 2주가 지난 판을 고정한다
OTEL_VERSION=2.32.0
OTEL_JAR_URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$OTEL_VERSION/opentelemetry-javaagent.jar"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

if [ -n "${LOCAL:-}" ]; then
  [ -f "$LC_INFRA_DIR/observability/local/.env" ] || die "LC_INFRA_DIR 에 lc-infra 경로를 준다. 그 observability/local/.env 의 서명 키를 쓴다"
  docker inspect "$LOCAL_PROJECT-app-1" > /dev/null 2>&1 || die "로컬 구성이 떠 있지 않다. lc-infra 의 observability/local 에서 docker compose up -d --build 를 돌린다"
else
  [ -n "${AWS_PROFILE:-}${AWS_ACCESS_KEY_ID:-}" ] || die "AWS_PROFILE 을 준다. member-env.sh up 을 돌린 계정의 프로필이다"

  #
  # 앱과 같은 커밋의 시나리오를 쓴다. 브랜치 기본값만 두면 up --sha 로 예전 커밋을 띄웠을 때
  # 앱은 예전인데 시나리오와 시드는 최신 develop 이라, 경로나 스키마가 바뀐 커밋이면 앱이 아니라 시나리오가 틀린다.
  # switch 뒤의 실행은 바뀐 current-sha 를 읽으므로 새 커밋의 시나리오를 받는다.
  #
  REF="${LOADTEST_REF:-}"
  if [ -z "$REF" ]; then
    REF=$(aws ssm get-parameter --name "/$PROJECT/current-sha" --query Parameter.Value --output text 2> /dev/null || true)
    case "$REF" in ""|bootstrap|None) REF=develop ;; esac
  fi
fi

# 이미지 태그는 40자 SHA 다. 짧은 SHA 를 받으면 GitHub 에 물어 늘린다
full_sha() {
  [ ${#1} -eq 40 ] && { echo "$1"; return; }
  curl -fsS "https://api.github.com/repos/$IMAGE_REPO/commits/$1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])'
}

# GHCR 공개 패키지에 그 태그가 있는지 본다. 0 있음, 1 없음, 2 확인 못 함
image_exists() {
  local token code
  token=$(curl -fsS "https://ghcr.io/token?scope=repository:$IMAGE_REPO:pull" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])') || return 2
  code=$(curl -sS -o /dev/null -w '%{http_code}' -I -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json,application/vnd.docker.distribution.manifest.list.v2+json" \
    "https://ghcr.io/v2/$IMAGE_REPO/manifests/$1") || return 2
  case "$code" in 200) return 0 ;; 404) return 1 ;; *) return 2 ;; esac
}

instance() {
  local id
  # 정해 둔 인스턴스가 있으면 그것을 쓴다. 새 이미지가 뜨지 못한 서버를 피할 때 쓴다
  if [ "$1" = app ] && [ -n "${LT_APP:-}" ]; then echo "$LT_APP"; return; fi
  id=$(aws ec2 describe-instances --filters "Name=tag:Project,Values=$PROJECT" "Name=tag:Role,Values=$1" \
    "Name=instance-state-name,Values=running" --query 'Reservations[0].Instances[0].InstanceId' --output text)
  [ "$id" != "None" ] || die "떠 있는 $1 인스턴스가 없다. member-env.sh status 로 환경을 확인한다"
  echo "$id"
}

instances() {
  aws ec2 describe-instances --filters "Name=tag:Project,Values=$PROJECT" "Name=tag:Role,Values=$1" \
    "Name=instance-state-name,Values=running" --query 'Reservations[].Instances[].InstanceId' --output text
}

# 스크립트를 base64 로 실어 bash 로 돌린다. AWS-RunShellScript 는 sh 로 돌고 따옴표가 섞이면 깨진다
remote() {
  local id="$1" timeout="$2" script="$3" params cmd status
  params=$(python3 -c 'import json,sys; print(json.dumps({"commands":["echo "+sys.argv[1]+" | base64 -d > /tmp/lt-job.sh && bash /tmp/lt-job.sh"],"executionTimeout":[sys.argv[2]]}))' \
    "$(printf '%s' "$script" | base64 | tr -d '\n')" "$timeout")
  cmd=$(aws ssm send-command --instance-ids "$id" --document-name AWS-RunShellScript --parameters "$params" \
    --timeout-seconds 600 --query Command.CommandId --output text)
  while :; do
    sleep 5
    status=$(aws ssm get-command-invocation --command-id "$cmd" --instance-id "$id" --query Status --output text 2> /dev/null || echo Pending)
    case "$status" in Pending|InProgress|Delayed) ;; *) break ;; esac
  done
  aws ssm get-command-invocation --command-id "$cmd" --instance-id "$id" --query StandardOutputContent --output text
  [ "$status" = "Success" ] || {
    aws ssm get-command-invocation --command-id "$cmd" --instance-id "$id" --query StandardErrorContent --output text | tail -20 >&2
    die "$status"
  }
}

# 앱 컨테이너의 접속 정보로 RDS 에 붙는다. 부하 생성기는 DB 보안 그룹이 받지 않아 앱 서버에서 돈다
# 컨테이너 이름만 따옴표를 끊어 여기서 넣는다. 나머지는 원격에서 풀려야 해서 작은따옴표로 둔다
DB_ENV='
ENVS=$(docker inspect '"$PROJECT"' --format "{{range .Config.Env}}{{println .}}{{end}}")
URL=$(echo "$ENVS" | grep "^DB_URL=" | cut -d= -f2-)
DBU=$(echo "$ENVS" | grep "^DB_USERNAME=" | cut -d= -f2-)
DBP=$(echo "$ENVS" | grep "^DB_PASSWORD=" | cut -d= -f2-)
HOST=$(echo "$URL" | sed -E "s#jdbc:mysql://([^:/]+).*#\1#")
DB=$(echo "$URL" | sed -E "s#jdbc:mysql://[^/]+/([^?]+).*#\1#")
mysql() { docker run --rm -i -e MYSQL_PWD="$DBP" mysql:8.4 mysql -h "$HOST" -u "$DBU" "$DB" "$@"; }
D=$(TZ=Asia/Seoul date +%F)
'

# 로컬 구성의 MySQL 이다. 계정은 lc-infra 의 observability/local/compose.yaml 이 정한다
local_mysql() { docker exec -i "$LOCAL_PROJECT-mysql-1" env MYSQL_PWD=root mysql -uroot lunchcatch "$@"; }

#
# 앱의 데이터베이스를 지우고 같은 이름, 문자셋, 정렬 규칙으로 다시 만든다. 앱이 다시 뜰 때 Flyway 가 빈 데이터베이스에 표와 기준 데이터를 처음부터 만든다(#1296).
# 표마다 시험 범위를 골라 지우면 시드의 목록에 없는 표(분석의 event_log, 쿠폰 재고)가 버전 사이에 쌓인다.
# 지운 뒤에는 이 연결의 기본 데이터베이스가 없어져 이름을 먼저 변수로 읽어 둔다.
#
RECREATE_DATABASE="SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME INTO @db, @cs, @co
  FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE();
SET @s = CONCAT('DROP DATABASE ', CHAR(96), @db, CHAR(96));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @s = CONCAT('CREATE DATABASE ', CHAR(96), @db, CHAR(96), ' CHARACTER SET ', @cs, ' COLLATE ', @co);
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SELECT CONCAT('database ', @db, ' tables ', COUNT(*)) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @db;"

#
# 다시 띄운 앱이나 배치가 준비되기를 5분까지 기다린다(#1347). 꺼진 채(exited, dead)로 두 번 연달아 보이면 다시 뜨지 않는 것이라
# 기다리지 않고 마지막 오류 로그를 보이고 실패한다. 다시 띄우기 정책이 있으면 꺼져도 곧 restarting 이 되어 계속 기다린다.
# 기다리는 동안 다시 떴으면 그 기동을 처음부터 5분 기다린다. 다시 뜬 횟수가 READY_MAX_RESTARTS 를 넘으면 계속 꺼지는 것이라 멈춘다(#1367).
# 준비된 뒤 다시 뜬 적이 있으면 그 횟수를 낸다. 다시 떠서 이어진 회차인지 나중에 알 수 있게 한다.
# 로컬은 local_ready 를 부르고, 부하 시험 환경은 READY_SH 를 서버에서 돌린다. 둘은 같은 순서다
#
# lc-infra 의 경보 ContainerRestartLoop(10분에 3번)과 맞춘 값이다(작성자가 2026-11-26 대화에서 정했다)
READY_MAX_RESTARTS=3
# 꺼졌거나 너무 많이 다시 뜬 컨테이너의 마지막 오류 로그를 보인다
ready_logs() {
  local name="$1" errs
  # 로거가 뜨기 전에 꺼지면(jar 를 못 찾는 등) JSON 오류 줄이 없다. 그때는 마지막 몇 줄을 그대로 보인다
  errs=$(docker logs --tail 300 "$name" 2>&1 | grep '"level":"ERROR"' | tail -2 | cut -c1-600 || true)
  [ -n "$errs" ] || errs=$(docker logs --tail 5 "$name" 2>&1 | cut -c1-600 || true)
  printf '%s\n' "$errs" >&2
}
local_ready() {
  local svc="$1" name="$LOCAL_PROJECT-$1-1" waited=0 seen st down=0 restarts
  seen=$(docker inspect -f '{{.RestartCount}}' "$name" 2> /dev/null || echo 0)
  local base="$seen"
  while ! docker exec "$name" curl -fs localhost:8081/actuator/health/readiness > /dev/null 2>&1; do
    restarts=$(docker inspect -f '{{.RestartCount}}' "$name" 2> /dev/null || echo "$seen")
    [ "$restarts" -le "$seen" ] || { seen=$restarts; waited=0; }
    st=$(docker inspect -f '{{.State.Status}}' "$name" 2> /dev/null || echo missing)
    case "$st" in
      exited|dead|missing) down=$((down + 1)) ;;
      *) down=0 ;;
    esac
    if [ "$down" -ge 2 ] || [ $((restarts - base)) -gt "$READY_MAX_RESTARTS" ]; then
      [ "$down" -ge 2 ] && echo "ERROR: $svc 컨테이너가 꺼졌다($st). 마지막 오류 로그다" >&2 \
        || echo "ERROR: $svc 가 기다리는 동안 $((restarts - base)) 번 다시 떠 ${READY_MAX_RESTARTS} 번을 넘었다. 마지막 오류 로그다" >&2
      ready_logs "$name"
      die "$svc 가 뜨지 못했다"
    fi
    [ "$waited" -lt 300 ] || die "$svc 가 5분 안에 준비되지 않았다"
    sleep 5; waited=$((waited + 5))
  done
  restarts=$(docker inspect -f '{{.RestartCount}}' "$name")
  # 다시 뜬 바로 뒤에 준비 확인이 성공하면 반복문 안의 검사를 건너뛴다. 그래서 준비된 뒤에도 횟수를 다시 본다
  if [ $((restarts - base)) -gt "$READY_MAX_RESTARTS" ]; then
    echo "ERROR: $svc 가 기다리는 동안 $((restarts - base)) 번 다시 떠 ${READY_MAX_RESTARTS} 번을 넘었다. 마지막 오류 로그다" >&2
    ready_logs "$name"
    die "$svc 가 뜨지 못했다"
  fi
  [ "$restarts" -eq 0 ] || echo "WARN: $svc 가 뜨는 동안 $restarts 번 다시 떴다. docker logs $name 로 그 오류를 본다" >&2
}

IFS= read -r -d '' READY_SH <<'SH' || true
ready_logs() {
  errs=$(docker logs --tail 300 "$PROJECT" 2>&1 | grep '"level":"ERROR"' | tail -2 | cut -c1-600 || true)
  [ -n "$errs" ] || errs=$(docker logs --tail 5 "$PROJECT" 2>&1 | cut -c1-600 || true)
  printf '%s\n' "$errs"
}
down=0; waited=0
seen=$(docker inspect "$PROJECT" --format '{{.RestartCount}}' 2> /dev/null || echo 0); base=$seen
until curl -fs localhost:8081/actuator/health/readiness > /dev/null; do
  restarts=$(docker inspect "$PROJECT" --format '{{.RestartCount}}' 2> /dev/null || echo "$seen")
  [ "$restarts" -le "$seen" ] || { seen=$restarts; waited=0; }
  st=$(docker inspect "$PROJECT" --format '{{.State.Status}}' 2> /dev/null || echo missing)
  case "$st" in exited|dead|missing) down=$((down + 1)) ;; *) down=0 ;; esac
  if [ "$down" -ge 2 ] || [ $((restarts - base)) -gt "$READY_MAX_RESTARTS" ]; then
    [ "$down" -ge 2 ] && echo "ERROR: 컨테이너가 꺼졌다($st). 마지막 오류 로그다" \
      || echo "ERROR: 기다리는 동안 $((restarts - base)) 번 다시 떠 $READY_MAX_RESTARTS 번을 넘었다. 마지막 오류 로그다"
    ready_logs
    exit 1
  fi
  [ "$waited" -lt 300 ] || { echo "ERROR: 5분 안에 준비되지 않았다"; exit 1; }
  sleep 5; waited=$((waited + 5))
done
restarts=$(docker inspect "$PROJECT" --format '{{.RestartCount}}' 2> /dev/null || echo 0)
if [ $((restarts - base)) -gt "$READY_MAX_RESTARTS" ]; then
  echo "ERROR: 기다리는 동안 $((restarts - base)) 번 다시 떠 $READY_MAX_RESTARTS 번을 넘었다. 마지막 오류 로그다"
  ready_logs
  exit 1
fi
[ "$restarts" = 0 ] || echo "WARN: 뜨는 동안 $restarts 번 다시 떴다. docker logs $PROJECT 로 그 오류를 본다"
SH

#
# 회차의 출발점을 만든다. 앱의 데이터베이스를 다시 만들고 앱과 배치를 다시 띄워 Flyway 가 표와 기준 데이터를 처음부터 만들게 한 뒤 Valkey 를 비운다(#1296).
# 도메인마다 다른 것은 시드뿐이라 도메인의 run.sh 는 이것을 부른 뒤 자기 시드를 넣는다.
# 로컬은 앱과 배치를 내린 뒤 지운다. 부하 시험 환경은 접속 정보를 앱 컨테이너에서 읽어야 해(DB_ENV) 앱이 떠 있는 채로 지우고 모든 서버를 다시 띄운다.
# 그 사이 앱과 배치의 쿼리는 실패한다. 다시 뜨는 동안 헬스체크가 실패해 오토스케일링 그룹이 서버를 바꾸지 않게 교체를 멈춰 둔다(switch 와 같은 방법).
#
reset_state() {
  local svc i id pids rc apps batches
  if [ -n "$LOCAL" ]; then
    docker stop "$LOCAL_PROJECT-app-1" "$LOCAL_PROJECT-batch-1" > /dev/null
    printf '%s\n' "$RECREATE_DATABASE" | local_mysql -N
    docker start "$LOCAL_PROJECT-app-1" "$LOCAL_PROJECT-batch-1" > /dev/null
    for svc in app batch; do
      local_ready "$svc"
    done
    docker exec "$LOCAL_PROJECT-valkey-1" valkey-cli FLUSHALL > /dev/null
    echo "valkey $(docker exec "$LOCAL_PROJECT-valkey-1" valkey-cli DBSIZE)"
    return
  fi
  # 데이터베이스를 지운 뒤에 서버를 찾지 못하면 다시 띄우지 못해 표 없이 남는다. 지우기 전에 앱과 배치를 찾아 둔다
  apps=$(instances app) || die "앱 서버를 찾지 못했다. member-env.sh status 로 환경을 확인한다"
  batches=$(instances batch) || die "배치 서버를 찾지 못했다. member-env.sh status 로 환경을 확인한다"
  [ -n "$apps" ] || die "떠 있는 앱 서버가 없다"
  [ -n "$batches" ] || die "떠 있는 배치 서버가 없다"
  remote "$(instance app)" 300 "set -euo pipefail
$DB_ENV
mysql -N <<'SQL'
$RECREATE_DATABASE
SQL"
  aws autoscaling suspend-processes --auto-scaling-group-name "$PROJECT-app" --scaling-processes ReplaceUnhealthy
  pids=""; rc=0
  for id in $apps $batches; do
    echo "restart $id"
    # 다시 뜰 때마다 5분을 새로 기다려, 다시 뜨기 READY_MAX_RESTARTS 번까지의 시간을 준다
    remote "$id" 1800 "set -euo pipefail
systemctl restart $PROJECT
PROJECT=$PROJECT
READY_MAX_RESTARTS=$READY_MAX_RESTARTS
$READY_SH
curl -fs localhost:8081/actuator/health/readiness" &
    pids="$pids $!"
  done
  for id in $pids; do
    wait "$id" || rc=1
  done
  aws autoscaling resume-processes --auto-scaling-group-name "$PROJECT-app" --scaling-processes ReplaceUnhealthy
  [ "$rc" -eq 0 ] || die "다시 띄우지 못한 서버가 있다. 위 출력에서 그 서버를 확인한다"
  remote "$(instance app)" 300 "set -euo pipefail
CACHE=\$(docker inspect $PROJECT --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^VALKEY_HOST=' | cut -d= -f2-)
docker run --rm valkey/valkey:9.0-alpine valkey-cli -h \"\$CACHE\" FLUSHALL
docker run --rm valkey/valkey:9.0-alpine valkey-cli -h \"\$CACHE\" DBSIZE"
}

# 로컬 구성의 앱이 쓰는 서명 키다. 출력하지 않고 변수로만 넘긴다
local_jwt() { grep '^JWT_SECRET=' "$LC_INFRA_DIR/observability/local/.env" | cut -d= -f2-; }

# 지금 시각의 밀리초다. 맥의 date 는 %3N 을 모른다
MS_FN='ms() { python3 -c "import time; print(int(time.time() * 1000))"; }'

#
# 부하 생성기에서 스크립트를 돌린다. 스크립트는 저장소 루트에서 돌고 BASE_URL, JWT, OUT(로그를 둘 디렉터리)을 쓴다.
# 시험 환경은 부하 생성기 인스턴스가 앱과 같은 커밋($REF)의 저장소를 받아 돌린다.
# 로컬은 이 기계의 k6 가 지금 작업 트리로 돌고 지표를 로컬 Prometheus 로 보낸다.
#
loadgen() {
  local timeout="$1" script="$2"
  if [ -n "${LOCAL:-}" ]; then
    (
      export BASE_URL="http://localhost:${APP_PORT:-18080}" JWT OUT
      JWT=$(local_jwt)
      OUT=$(mktemp -d)
      export K6_PROMETHEUS_RW_SERVER_URL="http://localhost:${PROMETHEUS_PORT:-19090}/api/v1/write"
      export K6_PROMETHEUS_RW_PUSH_INTERVAL=5s K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true
      cd "$ROOT"
      bash -c "set -euo pipefail
$MS_FN
$script"
    )
    return
  fi
  remote "$(instance load-test)" "$timeout" "set -euo pipefail
set -a; . /opt/loadtest/env; set +a
DIR=/opt/loadtest/lc-backend-run
OUT=/opt/loadtest
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=\"\$DIR\"
[ -d \"\$DIR/.git\" ] || git clone -q --depth 1 --no-checkout $REPO \"\$DIR\"
git -C \"\$DIR\" fetch -q --depth 1 origin $REF && git -C \"\$DIR\" reset -q --hard FETCH_HEAD
echo \"scenario \$(git -C \"\$DIR\" rev-parse --short HEAD)\"
cd \"\$DIR\"
$MS_FN
JWT=\$(aws ssm get-parameter --name /$PROJECT/jwt-signing-key --with-decryption --region $REGION --query Parameter.Value --output text)
$script"
}

# Prometheus 에 식 하나를 지금 시각으로 묻고 값 하나를 낸다. 값이 없으면 NA 다
IFS= read -r -d '' PROM_VALUE_PY <<'PY' || true
import json, sys, urllib.parse, urllib.request
r = json.load(urllib.request.urlopen(sys.argv[1] + '/api/v1/query?' + urllib.parse.urlencode({'query': sys.argv[2]})))['data']['result']
print(round(float(r[0]['value'][1]), 2) if r else 'NA')
PY

prom_value() {
  local expr="$1"
  if [ -n "${LOCAL:-}" ]; then
    python3 -c "$PROM_VALUE_PY" "http://localhost:${PROMETHEUS_PORT:-19090}" "$expr"
    return
  fi
  remote "$(instance monitoring)" 60 "cat > /tmp/lt-prom-value.py <<'PY'
$PROM_VALUE_PY
PY
python3 /tmp/lt-prom-value.py http://localhost:9090 '$expr'"
}

#
# 워밍업이다(#1267, #1270). 도메인의 run.sh 가 warmup) 분기에서 회차 이름과 그 도메인의 run 명령을 넘겨 부른다.
# run 명령은 목표 부하 구간을 WARMUP_HOLD 초로 돌리고, 출력에 hold <시작> <끝> 과 window <시작> <끝>(밀리초) 줄을 낸다.
# hold 는 목표 부하 구간이고 window 는 k6 가 실제로 돈 구간이다. 이 두 줄이 도메인의 run 이 지킬 약속이다.
# 정한 시간을 데워 버전 사이의 조건을 맞춘다. 5분은 로컬에서 길게 돌렸을 때 앱 CPU 가 트레이싱 켬과 끔 모두 목표 부하 3분째부터 평평했던 것에 여유를 더한 값이다.
# JIT 컴파일은 끝내는 조건으로 쓰지 않는다. 트레이싱 에이전트를 붙이면 부하가 일정해도 초당 10 ~ 25ms 로 남아 내려오지 않는다.
# 끝나기 직전 3분의 앱 CPU 흔들림(1분 평균의 최대와 최소의 차)이 WARMUP_CPU_SPREAD 를 넘으면 덜 데워졌다고 알린다. JIT 은 적기만 한다.
#
WARMUP_HOLD=300
WARMUP_CPU_SPREAD=0.05
warmup() {
  local name="$1" out rc hold_end k6_end at cpu1m spread jit
  shift
  case "$name" in *-warmup) ;; *) die "워밍업 회차는 이름을 -warmup 으로 끝낸다. 예: v1-warmup" ;; esac
  #
  # run 이 끝난 뒤 그 출력의 hold 줄과 window 줄로 판정한다. 부하가 언제 걸렸는지 짐작해 기다리지 않는다.
  # run 은 k6 의 실패를 삼키고 0 으로 끝나므로, k6 가 목표 부하 구간 끝 전에 멈췄으면 여기서 실패로 본다.
  # k6 는 정상이면 구간 끝 뒤에 끝난다. 구간 끝은 k6 의 시작 기록에서 내고, 그 기록이 없으면 run 을 띄운 더 이른 시각으로 내므로 여유를 두지 않는다.
  #
  out=$(mktemp)
  set +e
  "$@" 2>&1 | tee "$out"
  rc=${PIPESTATUS[0]}
  set -e
  read -r _ _ hold_end < <(grep -m1 '^hold ' "$out" || echo "hold 0 0")
  read -r _ _ k6_end < <(grep -m1 '^window ' "$out" || echo "window 0 0")
  rm -f "$out"
  if [ "$rc" -ne 0 ] || [ "$hold_end" -eq 0 ] || [ "$k6_end" -lt "$hold_end" ]; then
    echo "warmup-result $name failed rc=$rc"
    die "$name 의 run 이 실패했거나 k6 가 목표 부하 구간 끝 전에 멈췄다. run 이 남긴 k6 로그를 본다"
  fi
  # 구간 끝 5초 전을 기준으로 직전 3분을 묻는다. @ 로 시각을 정해 k6 가 끝난 뒤의 부하 없는 구간이 섞이지 않게 한다
  at=$(( hold_end / 1000 - 5 ))
  cpu1m='avg(avg_over_time(process_cpu_usage{job="app"}[1m]))'
  spread=$(prom_value "max_over_time(($cpu1m)[3m:1m] @ $at) - min_over_time(($cpu1m)[3m:1m] @ $at)") || spread=NA
  jit=$(prom_value "max(rate(jvm_compilation_time_ms_total{job=\"app\"}[3m] @ $at))") || jit=NA
  echo "warmup-result $name hold ${WARMUP_HOLD}s cpu_spread=$spread jit=$jit ms/s"
  python3 -c "import sys; sys.exit(0 if float('$spread') <= $WARMUP_CPU_SPREAD else 1)" 2> /dev/null \
    || echo "WARN: $name 의 끝 3분 앱 CPU 흔들림이 ${WARMUP_CPU_SPREAD} 를 넘었다(또는 읽지 못했다). 이 버전의 결과에 덜 데워졌다고 적는다" >&2
}

# k6 의 시간 꼴(5m, 300s, 1m30s, 1h)을 초로 바꾼다
k6_seconds() {
  local rest="$1" total=0 unit
  while [[ "$rest" =~ ^([0-9]+)(h|m|s)(.*)$ ]]; do
    case "${BASH_REMATCH[2]}" in h) unit=3600 ;; m) unit=60 ;; s) unit=1 ;; esac
    total=$(( total + 10#${BASH_REMATCH[1]} * unit ))
    rest="${BASH_REMATCH[3]}"
  done
  [ -z "$rest" ] && [ "$total" -gt 0 ] || die "시간은 k6 의 꼴로 준다. 예: 5m, 300s, 1m30s, 1h"
  echo "$total"
}

now_ms() { echo $(( $(date +%s) * 1000 )); }

#
# 서버 지표를 묻는다. 인자는 Prometheus 주소, 시작 ms, 끝 ms 이고 물을 지표는 표준 입력으로 받는다.
# 한 줄에 "이름 PromQL" 이고 PromQL 의 $W 는 구간 길이로 바뀐다. # 으로 시작하는 줄은 건너뛴다.
#
IFS= read -r -d '' METRICS_PY <<'PY' || true
import json, sys, urllib.parse, urllib.request
base, s, e = sys.argv[1], int(sys.argv[2]) / 1000, int(sys.argv[3]) / 1000
w = f'{int(e - s)}s'
def q(expr):
    u = base + '/api/v1/query?' + urllib.parse.urlencode({'query': expr, 'time': e})
    r = json.load(urllib.request.urlopen(u))['data']['result']
    return [({k: v for k, v in x['metric'].items() if k in ('uri', 'status', 'method')}, round(float(x['value'][1]), 3)) for x in r]
for line in sys.stdin:
    line = line.strip()
    if not line or line.startswith('#'):
        continue
    name, expr = line.split(' ', 1)
    print(name, q(expr.replace('$W', w)))
PY

# 도메인과 상관없이 묻는 지표다. 도메인의 것은 DOMAIN_METRICS 가 뒤에 더한다
COMMON_METRICS='
hikari_pending_max max_over_time(sum(hikaricp_connections_pending{job=~".*app.*"})[$W:5s])
hikari_active_max max_over_time(sum(hikaricp_connections_active{job=~".*app.*"})[$W:5s])
hikari_timeout_inc sum(increase(hikaricp_connections_timeout_total[$W]))
hikari_acquire_avg_ms 1000*sum(increase(hikaricp_connections_acquire_seconds_sum[$W]))/sum(increase(hikaricp_connections_acquire_seconds_count[$W]))
hikari_usage_avg_ms 1000*sum(increase(hikaricp_connections_usage_seconds_sum[$W]))/sum(increase(hikaricp_connections_usage_seconds_count[$W]))
mysql_commit_per_s sum(rate(mysql_global_status_commands_total{command="commit"}[$W]))
mysql_insert_per_s sum(rate(mysql_global_status_commands_total{command="insert"}[$W]))
mysql_threads_running_max max_over_time(max(mysql_global_status_threads_running)[$W:5s])
app_cpu_avg avg(avg_over_time(process_cpu_usage{job=~".*app.*"}[$W]))
app_cpu_max max(max_over_time(process_cpu_usage{job=~".*app.*"}[$W]))
loadgen_cpu_max_pct max_over_time((100*(1-avg(rate(node_cpu_seconds_total{role="load-test",mode="idle"}[1m]))))[$W:15s])
http_5xx_inc sum by (uri, status) (increase(http_server_requests_seconds_count{status=~"5.."}[$W]))
'

# 결과 문서의 상세 표에서 지표마다 적는 뜻이다. 한 줄에 "이름 뜻" 이다. 도메인의 것은 DOMAIN_METRIC_NOTES 가 뒤에 더한다
COMMON_METRIC_NOTES='
hikari_pending_max DB 연결을 기다린 요청 수의 최대(앱 서버 합)
hikari_active_max 쓰고 있는 DB 연결 수의 최대(앱 서버 합)
hikari_timeout_inc DB 연결을 얻지 못하고 시간이 초과된 수
hikari_acquire_avg_ms DB 연결을 얻는 데 걸린 평균 시간(ms)
hikari_usage_avg_ms DB 연결을 빌려 쓴 평균 시간(ms)
mysql_commit_per_s MySQL 의 초당 커밋 수
mysql_insert_per_s MySQL 의 초당 INSERT 수
mysql_threads_running_max MySQL 에서 동시에 실행 중인 스레드 수의 최대
app_cpu_avg 앱 JVM 의 CPU 사용률 평균(1 이 한도 전부, 앱 서버 평균)
app_cpu_max 앱 JVM 의 CPU 사용률 최대(1 이 한도 전부)
loadgen_cpu_max_pct 부하 생성기 CPU 사용률(1분 평균)의 최대(%). 회차의 무효 판정에 쓴다
http_5xx_inc 앱이 낸 5xx 응답 수(uri, 상태 코드마다)
'

# 초를 CloudWatch 가 받는 UTC 시각으로 바꾼다. 리눅스와 WSL 의 date 는 -d @초 를, macOS 의 date 는 -r 초 를 받는다(#1228)
utc_iso() {
  date -u -d "@$1" +%FT%TZ 2> /dev/null || date -u -r "$1" +%FT%TZ
}

# 표준 입력으로 받은 "이름 PromQL" 줄들을 그 구간으로 Prometheus 에 묻는다. 로컬은 로컬 Prometheus 를, 부하 시험 환경은 모니터링 서버의 것을 묻는다
prom_metrics() {
  local start="$1" end="$2" queries
  queries=$(cat)
  if [ -n "${LOCAL:-}" ]; then
    printf '%s\n' "$queries" | python3 -c "$METRICS_PY" "http://localhost:${PROMETHEUS_PORT:-19090}" "$start" "$end"
    return
  fi
  remote "$(instance monitoring)" 300 "cat > /tmp/lt-metrics.py <<'PY'
$METRICS_PY
PY
python3 /tmp/lt-metrics.py http://localhost:9090 $start $end <<'Q'
$queries
Q"
}

# 회차 구간의 서버 지표를 모은다. 로컬은 로컬 Prometheus 만 묻는다. RDS 와 ElastiCache 가 없다
metrics() {
  local start="${1:?시작 ms 를 준다}" end="${2:?끝 ms 를 준다}"
  printf '%s\n' "$COMMON_METRICS${DOMAIN_METRICS:-}" | prom_metrics "$start" "$end"
  [ -z "${LOCAL:-}" ] || return 0
  # RDS 는 Prometheus 가 아니라 CloudWatch 에서 읽는다. t4g 라 CPU 크레딧이 바닥나면 기본 성능으로 떨어진다
  for m in CPUUtilization:Maximum CPUCreditBalance:Minimum; do
    printf 'rds_%s ' "${m%%:*}"
    aws cloudwatch get-metric-statistics --namespace AWS/RDS --metric-name "${m%%:*}" \
      --dimensions Name=DBInstanceIdentifier,Value="$PROJECT-db" --period 60 --statistics "${m##*:}" \
      --start-time "$(utc_iso $((start / 1000)))" --end-time "$(utc_iso $((end / 1000)))" \
      --query "sort_by(Datapoints,&Timestamp)[].${m##*:}" --output text
  done
  # ElastiCache 의 t4g 는 Unlimited 가 없어 CPU 크레딧이나 네트워크 버스트가 바닥나면 기준 성능으로 떨어진다
  for node in $(aws elasticache describe-replication-groups --replication-group-id "$PROJECT-cache" \
      --query 'ReplicationGroups[0].MemberClusters' --output text); do
    for m in EngineCPUUtilization:Maximum CPUCreditBalance:Minimum NetworkBandwidthInAllowanceExceeded:Sum NetworkBandwidthOutAllowanceExceeded:Sum; do
      printf 'valkey_%s_%s ' "$node" "${m%%:*}"
      aws cloudwatch get-metric-statistics --namespace AWS/ElastiCache --metric-name "${m%%:*}" \
        --dimensions Name=CacheClusterId,Value="$node" --period 60 --statistics "${m##*:}" \
        --start-time "$(utc_iso $((start / 1000)))" --end-time "$(utc_iso $((end / 1000)))" \
        --query "sort_by(Datapoints,&Timestamp)[].${m##*:}" --output text
    done
  done
}

#
# Loki 에 그 구간의 경고와 오류를 이벤트별로 묻는다. 인자는 Loki 주소, event 접두사, 시작 ms, 끝 ms, 기본 인증(없으면 빈 값)이다.
# 고르는 조건은 lc-infra 의 경고 로그 패널(lunch-catch/lc-infra#63)과 같다. 한 줄에 "수준 이벤트 건수" 를 많은 순으로 낸다.
#
IFS= read -r -d '' WARNINGS_PY <<'PY' || true
import base64, json, sys, urllib.parse, urllib.request
base, prefix, s, e, auth = sys.argv[1], sys.argv[2], int(sys.argv[3]) // 1000, int(sys.argv[4]) // 1000, sys.argv[5]
expr = ('sum by (level, ev) (count_over_time({container=~"lunchcatch.*", role=~"app|batch"} |~ "event=%s"'
        ' | json | level=~"WARN|ERROR" | regexp "event=(?P<ev>[A-Z_]+)" [%ds]))') % (prefix, max(e - s, 1))
req = urllib.request.Request(base + '/loki/api/v1/query?' + urllib.parse.urlencode({'query': expr, 'time': e}))
if auth:
    req.add_header('Authorization', 'Basic ' + base64.b64encode(auth.encode()).decode())
rows = [(x['metric'].get('level', '-'), x['metric'].get('ev', '-'), int(float(x['value'][1])))
        for x in json.load(urllib.request.urlopen(req))['data']['result']]
for level, ev, n in sorted(rows, key=lambda r: (-r[2], r[0], r[1])):
    print(level, ev, n)
PY

# 로컬 Loki 는 포트를 열지 않아 그라파나의 데이터 소스 프록시로 묻는다
warning_counts() {
  local start="$1" end="$2"
  if [ -n "${LOCAL:-}" ]; then
    python3 -c "$WARNINGS_PY" "http://localhost:${GRAFANA_PORT:-13000}/api/datasources/proxy/uid/loki" \
      "$DOMAIN_EVENT_PREFIX" "$start" "$end" admin:admin
    return
  fi
  remote "$(instance monitoring)" 120 "cat > /tmp/lt-warnings.py <<'PY'
$WARNINGS_PY
PY
python3 /tmp/lt-warnings.py http://localhost:3100 $DOMAIN_EVENT_PREFIX $start $end ''"
}

#
# 회차 구간에 앱과 배치의 지표 수집이 빠진 칸(15초) 수를 묻는다(#1222). 인자는 Prometheus 주소, 시작 ms, 끝 ms 다.
# 빠진 칸은 그 서버의 지표가 통째로 비어, 그 구간의 그래프와 판정이 "괜찮았다" 로 읽히지 않게 경고 로그와 함께 남긴다.
# 앱과 배치를 0 이어도 늘 적어, 버전끼리 견줄 때 세지 않은 것과 0 을 가른다. 대상이 구간 내내 없었으면 셀 수 없어 0 이다.
#
# 대상마다 받아야 할 칸에서 수집에 성공한 칸(up 이 1 인 표본)을 뺀다(#1267). 수집이 실패한 칸(up 이 0)과 표본이 아예 없는 칸을 함께 센다.
# 로컬은 Prometheus 가 앱과 같은 Docker 가상 머신에 있어, 가상 머신이 멈추면 up 이 0 으로도 남지 않는다. up 이 0 인 칸만 세면 그 회차를 놓친다.
# 회차 경계에서 한 칸이 어긋나는 것은 빼고 센다.
#
IFS= read -r -d '' SCRAPE_GAPS_PY <<'PY' || true
import json, sys, urllib.parse, urllib.request
base, s, e = sys.argv[1], int(sys.argv[2]) // 1000, int(sys.argv[3]) // 1000
w = max(e - s, 15)
def query(expr):
    r = json.load(urllib.request.urlopen(base + '/api/v1/query?' + urllib.parse.urlencode({'query': expr, 'time': e})))
    return {x['metric'].get('job'): float(x['value'][1]) for x in r['data']['result']}
ok = query('sum by (job) (sum_over_time(up{job=~"app|batch"}[%ds]))' % w)
targets = query('count by (job) (count_over_time(up{job=~"app|batch"}[%ds]))' % w)
for job in ('app', 'batch'):
    want = targets.get(job, 0) * (round(w / 15) - 1)
    print('수집', job.upper() + '_SCRAPE_MISSED', max(0, int(want - ok.get(job, 0))))
PY

scrape_gaps() {
  local start="$1" end="$2"
  if [ -n "${LOCAL:-}" ]; then
    python3 -c "$SCRAPE_GAPS_PY" "http://localhost:${PROMETHEUS_PORT:-19090}" "$start" "$end"
    return
  fi
  remote "$(instance monitoring)" 120 "cat > /tmp/lt-scrape-gaps.py <<'PY'
$SCRAPE_GAPS_PY
PY
python3 /tmp/lt-scrape-gaps.py http://localhost:9090 $start $end"
}

#
# 결과 문서에서 제목에 "경고 로그" 가 든 절의 표에 회차 줄을 적는다. 표가 없으면 절 끝에 만든다.
# 같은 회차의 줄이 이미 있으면 지우고 다시 적는다. 절이 없으면 3 으로 끝난다.
#
IFS= read -r -d '' WRITE_WARNINGS_PY <<'PY' || true
import sys
path, name = sys.argv[1], sys.argv[2]
rows = [l.split() for l in sys.stdin.read().splitlines() if l.strip()]
new = ['| %s | %s | %s | %s |' % (name, lv, ev, n) for lv, ev, n in rows]
# 지표 수집 줄(수준 "수집")은 늘 오므로 경고와 오류가 없는지는 그 밖의 줄로 가른다
if not any(lv != '수집' for lv, _, _ in rows):
    new.insert(0, '| %s | - | 없음 | 0 |' % name)
lines = open(path, encoding='utf-8').read().split('\n')
head = next((i for i, l in enumerate(lines) if l.startswith('#') and '경고 로그' in l), None)
if head is None:
    sys.exit(3)
end = next((i for i in range(head + 1, len(lines)) if lines[i].startswith('#')), len(lines))
table = [i for i in range(head + 1, end) if lines[i].startswith('|')]
if table:
    keep = [i for i in table[2:] if not lines[i].startswith('| %s |' % name)]
    at = table[0] + 2
    body = [lines[i] for i in keep] + new
    lines[at:table[-1] + 1] = body
else:
    last = max([i for i in range(head, end) if lines[i].strip()])
    lines[last + 1:last + 1] = ['', '| 회차 | 수준 | 이벤트 | 건수 |', '|---|---|---|---|'] + new
open(path, 'w', encoding='utf-8').write('\n'.join(lines))
PY

#
# 회차가 끝나면 경고 로그 건수를 출력하고 결과 문서에 적어 커밋한다. 이름이 -warmup, -profile, -tracing 으로 끝나는 회차는 적지 않는다.
# 트레이싱을 켠 측정 회차는 이름으로 가르지 않아 적는다.
# 로컬은 노트북의 수치라 출력만 한다. 커밋은 결과 문서 하나만 지금 브랜치에 하고 푸시하지 않는다.
#
record_warnings() {
  local name="$1" start="$2" end="$3" rows gaps rc=0
  #
  # 지표 수집이 빠진 칸 수는 결과에 적지 않는 회차와 경고 로그를 묻지 못한 회차에도 먼저 출력한다(#1267).
  # 빠진 칸이 있으면 그 구간의 앱이 멈췄거나 지표가 비어, 그 회차의 수치를 다른 회차와 견주지 않는다.
  #
  gaps=$(scrape_gaps "$start" "$end") || { echo "WARN: 지표 수집 실패 칸을 묻지 못했다" >&2; gaps=""; }
  echo "scrape gaps"
  printf '%s\n' "${gaps:-묻지 못함}"
  if printf '%s\n' "$gaps" | awk '$3 > 0 { found = 1 } END { exit !found }'; then
    echo "WARN: $name 의 구간에 지표 수집이 빠진 칸이 있다. 이 회차의 수치는 다른 회차와 견주지 않고, 원인을 본 뒤 다시 돈다" >&2
  fi
  case "$name" in *-warmup|*-profile|*-tracing) echo "warnings: $name 은 결과에 적지 않는다"; return 0 ;; esac
  # 회차 끝의 로그가 Alloy 를 거쳐 Loki 에 닿기를 기다린다
  sleep 10
  rows=$(warning_counts "$start" "$end") || { echo "WARN: 경고 로그를 묻지 못했다. 결과 문서에 적지 않았다" >&2; return 0; }
  echo "warnings"
  printf '%s\n' "${rows:-없음}"
  # 지표 수집이 빠진 칸 수도 같은 표에 적는다(#1222)
  rows=$(printf '%s\n%s\n' "$rows" "$gaps")
  [ -z "${LOCAL:-}" ] || return 0
  printf '%s\n' "$rows" | python3 -c "$WRITE_WARNINGS_PY" "$ROOT/$RESULT_DOC" "$name" || rc=$?
  case "$rc" in
    0) ;;
    3) echo "WARN: $RESULT_DOC 에 제목이 '경고 로그' 인 절이 없어 적지 않았다. 절의 머리말을 쓰고 다시 적는다" >&2; return 0 ;;
    *) echo "WARN: $RESULT_DOC 에 적지 못했다" >&2; return 0 ;;
  esac
  if ! git -C "$ROOT" diff --cached --quiet; then
    echo "WARN: 스테이징된 다른 파일이 있어 커밋하지 않았다. $RESULT_DOC 에는 적어 두었다" >&2
    return 0
  fi
  git -C "$ROOT" add -- "$RESULT_DOC"
  git -C "$ROOT" commit -q -m "test: $name 의 경고 로그 건수와 지표 수집 실패 칸을 결과에 적는다 (#1094)" -- "$RESULT_DOC"
  echo "committed $(git -C "$ROOT" rev-parse --short HEAD) $RESULT_DOC"
}

#
# 회차 하나가 끝난 뒤의 일을 잇는다(#1305). 도메인의 run 이 record_warnings 다음에 그 회차의 출력 파일로 부른다.
# 출력의 window 줄과 hold 줄에서 구간을 읽어, 그 구간으로 서버 지표를 모으고 lc-infra 의 캡처를 부른 뒤 무효 회차를 판정한다.
# 무효면 걸린 조건과 그 값을 출력하고 3 으로 끝난다. 캡처가 실패하면 4 로 끝난다. 어느 쪽이든 다음 회차로 넘어가지 않는다(#1088).
# 워밍업 회차는 결과에 적지 않고 판정을 warmup 이 따로 해서 건너뛴다.
# 도메인은 캡처의 도메인 이름을 DOMAIN_SNAPSHOT 으로 준다. 캡처를 버전 폴더에 바로 두려면 SNAPSHOT_OUT 을 준다.
#
#
# 버전 하나를 명령 하나로 돈다(#1306). 순서는 README 의 회차 순서다.
#   측정(--from measure, 기본): 앱 대수 고정 -> switch(트레이싱을 붙임) -> reset -> prepare -> warmup -> 측정 3회 -> 멈춤
#   진단(--from diag): 프로파일링을 붙인 진단 회차 하나 -> 멈춤
# 멈출 자리에서 요약과 이어서 할 명령을 출력하고 끝난다. 입력을 기다리지 않는다. 승인은 사람이 대화로 하고, 승인을 받은 쪽이 이어서 할 명령을 부른다.
# 회차마다의 지표, 캡처, 무효 판정은 도메인의 run 이 after_run 으로 한다(#1305). 무효(3)나 캡처 실패(4)면 그 자리에서 멈춘다.
#
# 도메인의 run.sh 가 정하는 것
#   version_bands <시나리오> <풀 끔이면 1>  WARMUP_BASE, RUN_BASES(측정 3회), DIAG_BASE 를 정한다. 회원 대역은 도메인의 부하 시험 문서가 갖는다
#   PREPARE_STEP                           1 이면 reset 뒤에 prepare 를 부른다
#   version_dir <버전 이름> <시나리오>      결과 문서 옆의 버전 폴더(저장소 기준 경로)를 출력한다. 회차 폴더와 결과 문서의 그림이 그 아래에 남는다(#1326)
#
# 부하 시험 문서 2장: 앱 오토스케일링 그룹이 회차 도중에 늘면 회차마다 조건이 달라져 버전끼리 비교할 수 없어 대수를 고정한다
APP_COUNT=3
pin_app_count() {
  [ -z "${LOCAL:-}" ] || return 0
  local asg="$PROJECT-app" n i
  aws autoscaling update-auto-scaling-group --auto-scaling-group-name "$asg" \
    --min-size "$APP_COUNT" --desired-capacity "$APP_COUNT" --max-size "$APP_COUNT" \
    || die "오토스케일링 그룹 $asg 의 대수를 $APP_COUNT 로 고정하지 못했다"
  for i in $(seq 1 60); do
    n=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$asg" \
      --query 'length(AutoScalingGroups[0].Instances[?LifecycleState==`InService` && HealthStatus==`Healthy`])' --output text)
    [ "$n" = "$APP_COUNT" ] && { echo "app count $n"; return 0; }
    sleep 10
  done
  die "앱이 $APP_COUNT 대로 서지 않았다(지금 $n 대). 오토스케일링 그룹 $asg 를 본다"
}

run_version() {
  local self="$1" name="$2"; shift 2
  local sha="" from=measure scenario="" nopool="" rate="" hold="" switch_args=() scen_args=() i rc base
  local baseline="" baseline_sha="" fixed=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --sha) sha="${2:?--sha 뒤에 이미지 커밋을 준다}"; shift 2 ;;
      # 견줄 앞 버전과 그 커밋, 이 버전에서 고친 함수다(#1343). 계획 실행기는 같은 시나리오의 바로 앞 줄을 준다
      --baseline) baseline="${2:?--baseline 뒤에 앞 버전 이름을 준다}"; shift 2 ;;
      --baseline-sha) baseline_sha="${2:?--baseline-sha 뒤에 앞 버전 커밋을 준다}"; shift 2 ;;
      --fixed) fixed+=("${2:?--fixed 뒤에 함수를 준다}"); shift 2 ;;
      --from) from="${2:?--from 뒤에 measure 나 diag 를 준다}"; shift 2 ;;
      --scenario) scenario="${2:?--scenario 뒤에 시나리오를 준다}"; scen_args=(--scenario "$2"); shift 2 ;;
      --no-pool) nopool=1; switch_args+=(--no-pool); shift ;;
      # 초당 피드와 유지 시간이다. 주지 않으면 도메인의 run 의 기본값이다. 로컬에서 짧게 돌려 볼 때 쓴다
      --rate) rate="${2:?--rate 뒤에 초당 피드를 준다}"; shift 2 ;;
      --hold) hold="${2:?--hold 뒤에 유지 시간을 준다}"; shift 2 ;;
      --env|--app-env|--batch-env) switch_args+=("$1" "${2:?$1 뒤에 KEY=VALUE 를 준다}"); shift 2 ;;
      *) die "version 이 모르는 선택지다: $1" ;;
    esac
  done
  [ -n "${LOCAL:-}" ] || [ -n "$sha" ] || die "부하 시험 환경의 version 은 --sha 로 이미지 커밋을 준다"
  version_bands "$scenario" "$nopool"
  #
  # 멈출 때 출력하는 이어서 할 명령이다. 그대로 복사해 부를 수 있게 인자마다 셸 인용을 붙이고(공백이 든 --app-env 값 등),
  # 부하 시험 환경이면 명령 앞에만 준 AWS_PROFILE 도 붙인다. 접근 키는 붙이지 않는다.
  #
  local again_args=("$self") again
  [ -z "${LOCAL:-}" ] || again_args+=(--local)
  again_args+=(version "$name")
  [ -z "$sha" ] || again_args+=(--sha "$sha")
  [ -z "$scenario" ] || again_args+=(--scenario "$scenario")
  again_args+=(${switch_args[@]+"${switch_args[@]}"})
  [ -z "$rate" ] || again_args+=(--rate "$rate")
  [ -z "$hold" ] || again_args+=(--hold "$hold")
  [ -z "$baseline" ] || again_args+=(--baseline "$baseline")
  [ -z "$baseline_sha" ] || again_args+=(--baseline-sha "$baseline_sha")
  # 고른 고친 함수도 남긴다. 빠지면 다시 돌린 진단 회차의 baseline.json 에 고친 함수가 없다
  for i in ${fixed[@]+"${fixed[@]}"}; do again_args+=(--fixed "$i"); done
  again=$(printf '%q ' "${again_args[@]}"); again="${again% }"
  [ -n "${LOCAL:-}" ] || [ -z "${AWS_PROFILE:-}" ] || again="AWS_PROFILE=$(printf '%q' "$AWS_PROFILE") $again"
  # 결과 폴더를 따로 주었으면 다시 부를 때도 같은 폴더를 쓴다. 아직 기본값을 채우기 전이라 여기서는 준 값만 있다
  [ -z "${SNAPSHOT_OUT:-}" ] || again="SNAPSHOT_OUT=$(printf '%q' "$SNAPSHOT_OUT") $again"
  #
  # 캡처와 결과(#1326)를 버전 폴더에 둔다. 회차 폴더는 그 아래 회차 이름이다. 로컬은 결과 문서 대신 build/loadtest-results/ 에 둔다.
  # 따로 준 SNAPSHOT_OUT 이 있으면 그것을 쓴다
  #
  local given_out="${SNAPSHOT_OUT:-}"
  if [ -z "${SNAPSHOT_OUT:-}" ]; then
    SNAPSHOT_OUT="$ROOT/$(version_dir "$name" "$scenario")"
    [ -z "${LOCAL:-}" ] || SNAPSHOT_OUT="$ROOT/build/loadtest-results/$(basename "$SNAPSHOT_OUT")"
  fi
  export SNAPSHOT_OUT
  mkdir -p "$SNAPSHOT_OUT"
  case "$from" in
    measure)
      pin_app_count
      # 측정 회차부터 트레이싱을 붙인다(#1177). 이름이 트레이스의 run 라벨이 된다
      if [ -n "${LOCAL:-}" ]; then
        "$self" --local switch --tracing "$name" ${switch_args[@]+"${switch_args[@]}"} || die "$name 의 switch 가 실패했다"
      else
        "$self" switch "$sha" --tracing "$name" ${switch_args[@]+"${switch_args[@]}"} || die "$name 의 switch 가 실패했다"
      fi
      "$self" ${LOCAL:+--local} reset || die "$name 의 reset 이 실패했다"
      [ "${PREPARE_STEP:-}" != 1 ] || "$self" ${LOCAL:+--local} prepare || die "$name 의 prepare 가 실패했다"
      "$self" ${LOCAL:+--local} warmup "$name-warmup" "$WARMUP_BASE" $rate ${scen_args[@]+"${scen_args[@]}"} || die "$name 의 워밍업이 실패했다"
      i=0
      for base in "${RUN_BASES[@]}"; do
        i=$((i + 1)); rc=0
        "$self" ${LOCAL:+--local} run "$name-run$i" "$base" ${rate:-100} ${hold:-5m} ${scen_args[@]+"${scen_args[@]}"} || rc=$?
        if [ "$rc" -ne 0 ]; then
          echo "STOP $name-run$i 가 $rc 로 끝났다(3 무효, 4 캡처 실패, 5 프로파일링 실패). 위 출력의 INVALID, SNAPSHOT-FAILED, PROFILE-FAILED 를 본다"
          echo "next 다시 재려면: $again"
          return "$rc"
        fi
      done
      # 앞 버전과의 고친 함수 후보다(#1343). 진단 회차의 Diff 와 비교 표가 그 함수까지 펼친다. 확인해 빼거나 더한 목록을 --fixed 로 넘긴다
      # 파일 이름에 버전을 넣는다. SNAPSHOT_OUT 을 버전끼리 함께 쓰면 뒤 버전이 앞 버전의 후보를 덮어쓰기 때문이다.
      # 다 뽑은 뒤에만 제 이름으로 옮긴다. 뽑다 실패하면 같은 버전의 앞 후보가 그대로 남는다
      local cand="$SNAPSHOT_OUT/$name-fixed-candidates.txt"
      if [ -n "$baseline_sha" ] && [ -n "$sha" ]; then
        if fixed_candidates "$baseline_sha" "$sha" > "$cand.part"; then
          mv "$cand.part" "$cand"
          echo "fixed 앞 버전 $baseline($baseline_sha) 과 견준 고친 함수 후보 $(grep -c . "$cand") 개: $cand"
        else
          rm -f "$cand.part"
          echo "WARN: 고친 함수 후보를 뽑지 못했다. $baseline_sha 와 $sha 가 이 저장소에 있는지 본다(git fetch --tags)" >&2
        fi
      fi
      echo "STOP measure-done $name. 측정 3회가 끝났다. 위의 회차마다 valid, metrics, scrape gaps, 캡처 폴더를 보고 진단 회차를 돌릴지 정한다"
      echo "next 진단을 돌리려면: $again --from diag"
      # 후보는 앞 버전과 이 버전 사이의 develop 작업이 많으면 수십 개가 된다. 명령에 붙이지 않고 고른 것만 넘긴다
      [ ! -s "$cand" ] \
        || echo "next 고친 함수는 $cand 의 후보에서 골라 --fixed <함수> 로 하나씩 붙인다"
      echo "next 진단 없이 다음 버전으로 가려면 다음 버전의 version 을 부른다"
      ;;
    diag)
      rc=0
      #
      # 진단 회차의 캡처와 진단 절에 넘긴다(#1343). 앞 버전의 진단 회차 폴더가 있으면 그것과 견주고, 이 Pyroscope 에 남아 있으면 Diff 그림도 찍는다.
      # 조건은 두 회차가 같은 조건인지 견주려고 남긴다
      #
      DIAG_BASELINE_DIR=""
      if [ -n "$baseline" ] && [ -n "$given_out" ]; then
        # SNAPSHOT_OUT 을 따로 주었으면 버전끼리 한 폴더를 쓴다. 앞 버전의 진단 회차도 그 아래에 있다
        DIAG_BASELINE_DIR="$given_out/$baseline-run1-profile"
      elif [ -n "$baseline" ]; then
        DIAG_BASELINE_DIR="$ROOT/$(version_dir "$baseline" "$scenario")"
        [ -z "${LOCAL:-}" ] || DIAG_BASELINE_DIR="$ROOT/build/loadtest-results/$(basename "$DIAG_BASELINE_DIR")"
        DIAG_BASELINE_DIR="$DIAG_BASELINE_DIR/$baseline-run1-profile"
      fi
      DIAG_FIXED=$(printf '%s\n' ${fixed[@]+"${fixed[@]}"})
      # 조건에는 switch 에 넘긴 설정(--env, --app-env, --batch-env)도 넣는다. 연결 풀처럼 설정으로 바뀐 것이 조건 차이로 보이게 한다.
      # 값은 남기지 않고 지문(sha256 앞 8자리)만 남긴다. 비밀이 들어 있어도 문서와 저장소에 남지 않는다
      local cond_args=() j
      for ((j = 0; j < ${#switch_args[@]}; j++)); do
        [ "${switch_args[$j]}" = --no-pool ] && continue
        cond_args+=("${switch_args[$j]}" "${switch_args[$((j + 1))]}"); j=$((j + 1))
      done
      DIAG_CONDITION=$(python3 -c '
import hashlib, json, sys
rate, hold, members, scenario, pool, *pairs = sys.argv[1:]
cond = dict(rate=rate, hold=hold, members=members, scenario=scenario, pool=pool)
for flag, pair in zip(pairs[::2], pairs[1::2]):
    key, _, value = pair.partition("=")
    cond["%s %s" % (flag.lstrip("-"), key)] = "값 " + hashlib.sha256(value.encode()).hexdigest()[:8]
print(json.dumps(cond, ensure_ascii=False))' \
        "${rate:-100}" "${hold:-5m}" "$DIAG_BASE" "${scenario:-feed_impression}" "$([ -n "$nopool" ] && echo off || echo on)" ${cond_args[@]+"${cond_args[@]}"})
      export DIAG_BASELINE_DIR DIAG_FIXED DIAG_CONDITION
      "$self" ${LOCAL:+--local} run "$name-run1-profile" "$DIAG_BASE" ${rate:-100} ${hold:-5m} ${scen_args[@]+"${scen_args[@]}"} --profile both || rc=$?
      [ "$rc" -eq 0 ] || { echo "STOP $name-run1-profile 가 $rc 로 끝났다(4 캡처 실패, 5 프로파일링 실패). 위 출력을 본다"; echo "next 다시 돌리려면: $again --from diag"; return "$rc"; }
      echo "STOP diag-done $name. 진단 회차가 끝났다. 진단 캡처(Call Tree, 트레이스, Redis 지표)를 보고 다음 버전으로 갈지 정한다"
      echo "next 다음 버전으로 가려면 다음 버전의 version 을 부른다"
      ;;
    *) die "--from 은 measure 나 diag 다" ;;
  esac
}

#
# 측정 계획 하나를 받아 버전 여럿을 차례로 돈다(#1307). 버전마다 run_version 을 부르고, 어디까지 했는지 상태 파일에 남겨 멈춘 자리부터 잇는다.
#
# 계획 파일은 한 줄에 버전 하나다. # 으로 시작하는 줄과 빈 줄은 건너뛴다.
#   <버전 이름> <커밋, 태그나 SHA> [ref=<LOADTEST_REF>] [version 의 선택지...]
# 커밋이 TBD 인 줄에 닿으면 멈춘다. 측정 직전에 정할 커밋이다.
# ref 는 그 버전의 시나리오와 시드 생성기를 받을 ref 다. 주지 않으면 떠 있는 앱의 커밋이다.
#
# 한 번 부르면 한 걸음만 간다. 승인을 받는 자리마다 멈추기 때문이다(#1088).
#   다음 버전의 측정 -> 멈춤(진단을 돌릴지 정한다) -> --diag 나 --skip-diag -> 멈춤(다음 버전으로 갈지 정한다) -> 다시 부르면 다음 버전
# 상태 파일은 build/loadtest-plans/<계획 파일 이름>.<local|lt>.state 다. 로컬과 부하 시험 환경을 따로 센다. 로컬로 먼저 돌린 기록이 부하 시험 환경의 버전을 건너뛰게 하지 않는다. 줄마다 버전, 단계, 시작과 끝의 UTC 시각이다. 버전마다 잰 시각을 결과 문서에 옮긴다(#1088).
#
run_plan() {
  local self="$1" plan="$2" choice="${3:-}" state line name commit ref args step started rc lines=() fixed=() scen prev_args i toks=()
  [ -f "$plan" ] || die "계획 파일이 없다: $plan"
  case "$choice" in ""|--diag|--skip-diag) ;; *) die "plan 의 선택지는 --diag 나 --skip-diag 다" ;; esac
  # --diag 뒤의 --fixed 는 진단 회차가 앞 버전과 견줄 때 고친 함수다(#1343)
  shift $(( $# < 3 ? $# : 3 ))
  while [ $# -gt 0 ]; do
    [ "$choice" = --diag ] && [ "$1" = --fixed ] || die "plan 의 --fixed 는 --diag 뒤에 준다"
    fixed+=(--fixed "${2:?--fixed 뒤에 함수를 준다}"); shift 2
  done
  # 앞 버전은 같은 시나리오(--scenario)인 바로 앞 줄이다. 노출 수집과 서빙처럼 시험이 다르면 견주지 않는다(#1343)
  local -a prev_names=() prev_commits=() prev_scens=()
  local target=lt
  [ -z "${LOCAL:-}" ] || target=local
  state="$ROOT/build/loadtest-plans/$(basename "$plan").$target.state"
  mkdir -p "$(dirname "$state")"; touch "$state"
  # 멈출 때 출력하는 다음 걸음 명령이다. run_version 의 것과 같게 인자마다 셸 인용을 붙이고 부하 시험 환경이면 AWS_PROFILE 을 앞에 붙인다
  local next_cmd
  next_cmd=$(printf '%q ' "$self" ${LOCAL:+--local} plan "$plan"); next_cmd="${next_cmd% }"
  [ -n "${LOCAL:-}" ] || [ -z "${AWS_PROFILE:-}" ] || next_cmd="AWS_PROFILE=$(printf '%q' "$AWS_PROFILE") $next_cmd"
  # 계획을 먼저 다 읽는다. 표준 입력으로 읽으며 돌면 안에서 부르는 명령(docker exec -i 등)이 남은 줄을 먹는다
  while IFS= read -r line || [ -n "$line" ]; do lines+=("$line"); done < "$plan"
  for line in ${lines[@]+"${lines[@]}"}; do
    line="${line%%#*}"; [ -n "${line//[[:space:]]/}" ] || continue
    read -r name commit args <<< "$line"
    # run_version 에는 $args 를 공백으로 나눠 넘긴다. 같은 방법으로 나눈 낱말에서 --scenario 의 값을 읽어 실제로 도는 시나리오와 맞춘다
    scen=""
    read -r -a toks <<< "$args"
    for (( i = 0; i < ${#toks[@]} - 1; i++ )); do
      [ "${toks[$i]}" != --scenario ] || scen="${toks[$((i + 1))]}"
    done
    # 시나리오를 주지 않으면 run_version 이 feed_impression 으로 돈다. 같은 시나리오로 견주게 맞춘다
    scen="${scen:-feed_impression}"
    prev_args=()
    for (( i = ${#prev_names[@]} - 1; i >= 0; i-- )); do
      [ "${prev_scens[$i]}" = "$scen" ] || continue
      prev_args=(--baseline "${prev_names[$i]}" --baseline-sha "${prev_commits[$i]}"); break
    done
    prev_names+=("$name"); prev_commits+=("$commit"); prev_scens+=("$scen")
    # 진단을 돌렸거나 건너뛴 버전은 끝났다
    awk -v n="$name" '$1 == n && ($2 == "diag-done" || $2 == "diag-skipped") { found = 1 } END { exit !found }' "$state" && continue
    [ "$commit" != TBD ] || die "$name 의 커밋을 정하지 않았다(TBD). 측정 직전에 정해 $plan 에 적는다"
    ref=""; step=""
    if [[ "$args" == ref=* ]] || [[ "$args" == *" ref="* ]]; then
      ref=$(sed -E 's/.*(^| )ref=([^ ]+).*/\2/' <<< "$args"); args=$(sed -E 's/(^| )ref=[^ ]+//' <<< "$args")
    fi
    awk -v n="$name" '$1 == n && $2 == "measure-done" { found = 1 } END { exit !found }' "$state" && step=measured
    started=$(date -u +%FT%TZ); rc=0
    if [ -z "$step" ]; then
      [ -z "$choice" ] || die "$name 은 아직 측정하지 않았다. --diag 나 --skip-diag 없이 부른다"
      echo "plan $name 측정을 시작한다($commit${ref:+, LOADTEST_REF=$ref})"
      # shellcheck disable=SC2086
      LOADTEST_REF="${ref:-${LOADTEST_REF:-}}" run_version "$self" "$name" --sha "$commit" $args ${prev_args[@]+"${prev_args[@]}"} || rc=$?
      [ "$rc" -eq 0 ] || { echo "plan $name 측정이 $rc 로 멈췄다. 고친 뒤 다시 부르면 이 버전의 측정부터 다시 한다"; return "$rc"; }
      echo "$name measure-done $started $(date -u +%FT%TZ)" >> "$state"
      echo "next 진단을 돌리려면: $next_cmd --diag"
      # 고친 함수는 run_version 이 보인 후보 파일에서 골라 --diag 뒤에 붙인다(#1343)
      [ ! -s "${SNAPSHOT_OUT:-/nonexistent}/$name-fixed-candidates.txt" ] \
        || echo "next 고친 함수를 붙이려면: $next_cmd --diag --fixed <함수> ... (후보: $SNAPSHOT_OUT/$name-fixed-candidates.txt)"
      echo "next 진단을 건너뛰려면: $next_cmd --skip-diag"
      return 0
    fi
    case "$choice" in
      --diag)
        # shellcheck disable=SC2086
        LOADTEST_REF="${ref:-${LOADTEST_REF:-}}" run_version "$self" "$name" --sha "$commit" $args ${prev_args[@]+"${prev_args[@]}"} \
          ${fixed[@]+"${fixed[@]}"} --from diag || rc=$?
        [ "$rc" -eq 0 ] || { echo "plan $name 진단이 $rc 로 멈췄다. 고친 뒤 다시 부른다: $next_cmd --diag$( [ ${#fixed[@]} -eq 0 ] || printf ' %q' "${fixed[@]}")"; return "$rc"; }
        echo "$name diag-done $started $(date -u +%FT%TZ)" >> "$state" ;;
      --skip-diag)
        echo "$name diag-skipped $started $started" >> "$state"
        echo "plan $name 의 진단을 건너뛰었다" ;;
      *)
        echo "STOP $name 은 측정이 끝났다. 진단을 돌릴지 정해 --diag 나 --skip-diag 로 부른다"
        return 0 ;;
    esac
    echo "STOP $name 이 끝났다. 다음 버전으로 가려면 --diag, --skip-diag 없이 다시 부른다"
    echo "next $next_cmd"
    return 0
  done
  echo "plan 계획의 버전을 모두 돌았다. 잰 시각은 $state 에 있다"
}

# 회차를 돌기 전에 캡처 스크립트가 있는지 본다. 회차를 다 돈 뒤에 없다는 것을 알면 그 회차를 다시 돌아야 한다
require_snapshot() {
  case "$1" in *-warmup) return 0 ;; esac
  [ -x "$LC_INFRA_DIR/scripts/loadtest-snapshot.sh" ] \
    || die "LC_INFRA_DIR 에 lc-infra 경로를 준다. 회차가 끝나면 그 scripts/loadtest-snapshot.sh 로 캡처한다(#1305)"
}

#
# 회차의 핵심 수치를 묻는다(#1326). API 마다 요청 수, 5xx 수, p50 / p95 / p99 다. 유지 구간만 센다.
# 분위수는 uri 만 단 히스토그램(http_server_requests_histogram_seconds, #1092)으로 내 같은 uri 의 메서드를 합쳐 센다.
# 요청 수와 5xx 는 메서드를 단 기존 지표로 센다.
#
CORE_METRICS='
core_count sum by (method, uri) (increase(http_server_requests_seconds_count{job=~".*app.*", uri!~"UNKNOWN|/actuator.*"}[$W]))
core_5xx sum by (method, uri) (increase(http_server_requests_seconds_count{job=~".*app.*", uri!~"UNKNOWN|/actuator.*", status=~"5.."}[$W]))
core_p50 histogram_quantile(0.5, sum by (uri, le) (increase(http_server_requests_histogram_seconds_bucket{job=~".*app.*", uri!~"UNKNOWN|/actuator.*"}[$W])))
core_p95 histogram_quantile(0.95, sum by (uri, le) (increase(http_server_requests_histogram_seconds_bucket{job=~".*app.*", uri!~"UNKNOWN|/actuator.*"}[$W])))
core_p99 histogram_quantile(0.99, sum by (uri, le) (increase(http_server_requests_histogram_seconds_bucket{job=~".*app.*", uri!~"UNKNOWN|/actuator.*"}[$W])))
'

#
# 회차의 기준 초과를 묻는다(#1339). 인자는 Prometheus 주소, Tempo 주소(없으면 빈 값), Tempo 의 기본 인증(없으면 빈 값), 유지 구간의 시작과 끝 ms 다.
# 기준은 앱이 내는 overrun_threshold_seconds{kind, name} 이다. 이름별 값이 없으면 그 종류의 default 로 판정하고 기본값이라고 남긴다.
# 판정은 06 의 기준을 넘은 패널(lc-infra 의 service 대시보드)과 같은 지표로 한다. 패널은 시점마다, 여기는 유지 구간 전체로 센다.
#   API          유지 구간의 p99 를 그 API 의 기준에 견준다. 넘었으면 기준을 넘은 루트 스팬 가운데 가장 오래 걸린 트레이스를 붙인다
#   메서드       그 API 아래에서 그 API 의 기준을 넘은 자식 스팬을 TraceQL 로 묻는다. 남은 트레이스(앞단 10% 와 테일 샘플링을 지난 것)만 센다
#   릴레이       발행 지연 p99(#1205). 미발행 건수와 가장 오래 기다린 미발행 건의 대기 시간의 최대를 참고로 둔다
#   워커         가장 오래 기다린 일의 대기 시간의 최대. 대기열 길이의 최대를 참고로 둔다
#   cron         실행 한 번에 걸린 시간의 최대. 릴레이로 보는 fixedDelay 작업은 대시보드처럼 빼고, 상태만 보는 작업은 판정하지 않는다
# 한 줄로 JSON 을 낸다. 부하 시험 환경은 SSM 출력 한도(24,000자) 안에 들게 모은 값만 낸다.
#
IFS= read -r -d '' OVERRUN_PY <<'PY' || true
import base64, json, math, sys, urllib.parse, urllib.request
prom, tempo, auth, hs, he = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
w, at = max(int((he - hs) / 1000), 1), he / 1000
RELAY_JOBS = 'ImpressionDispatchJob.dispatchPending|PaymentRecheckWorker.recheckUnknownPayments|RechargeReservationWorker.reserveCharged'
STATUS_JOBS = ('DailyJobScheduler.sendHeartbeat()', 'DailyJobScheduler.takeOverStale()')

def get(url):
    req = urllib.request.Request(url)
    if auth:
        req.add_header('Authorization', 'Basic ' + base64.b64encode(auth.encode()).decode())
    return json.load(urllib.request.urlopen(req, timeout=60))

def q(expr):
    r = get(prom + '/api/v1/query?' + urllib.parse.urlencode({'query': expr, 'time': at}))['data']['result']
    return [(x['metric'], float(x['value'][1])) for x in r if x['value'][1] not in ('NaN', '+Inf', '-Inf')]

def by(rows, label):
    return {m.get(label, ''): v for m, v in rows}

thr = {}
for m, v in q('max by (kind, name) (overrun_threshold_seconds)'):
    thr.setdefault(m['kind'], {})[m['name']] = v

def threshold(kind, name):
    named = thr.get(kind, {})
    if name in named:
        return named[name], False
    return named.get('default'), True

LIMIT, SPSS = 500, 100

def truncated(traces, limit, spss):
    # 상한에 닿았는지 본다. 트레이스가 상한만큼 왔거나, 맞은 스팬(matched)이 돌려받은 스팬보다 많으면 일부만 센 것이다
    if len(traces) >= limit:
        return True
    return any(int(ss.get('matched', 0)) > len(ss.get('spans', [])) for tr in traces for ss in tr.get('spanSets', []))

def search(traceql, limit=LIMIT, spss=SPSS):
    if not tempo:
        return None
    params = {'q': traceql, 'start': int(hs / 1000), 'end': int(he / 1000) + 1, 'limit': limit, 'spss': spss}
    return get(tempo + '/api/search?' + urllib.parse.urlencode(params)).get('traces', [])

def p99(values):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * 0.99) - 1)] if values else None

flt = 'job=~".*app.*", uri!~"UNKNOWN|/actuator.*"'
counts = q('sum by (method, uri) (increase(http_server_requests_seconds_count{%s}[%ds])) > 0' % (flt, w))
quant = by(q('histogram_quantile(0.99, sum by (uri, le) (increase(http_server_requests_histogram_seconds_bucket{%s}[%ds])))' % (flt, w)), 'uri')
# 추적의 상태다. Tempo 가 없으면 추적을 켜지 않은 환경이고, Tempo 는 있는데 이 구간에 남은 트레이스가 없으면
# 앱에 추적을 붙이지 않았거나 샘플링에서 모두 빠진 것이다. 둘은 여기서 가르지 못해 나눠 적는다
if not tempo:
    tracing = 'no-tempo'
else:
    try:
        tracing = 'ok' if search('{ }', limit=1, spss=1) else 'no-traces'
    except OSError:
        tracing = 'no-tempo'
traced = tracing == 'ok'
apis, methods = [], []
for m, _ in sorted(counts, key=lambda r: (r[0].get('uri', ''), r[0].get('method', ''))):
    name = '%s %s' % (m.get('method'), m.get('uri'))
    t, default = threshold('api', name)
    v = quant.get(m.get('uri'))
    row = {'name': name, 'threshold': t, 'default': default, 'p99': v,
           'over': None if v is None or t is None else v > t, 'trace': None}
    if traced and t is not None:
        roots = search('{ name = "%s" && duration > %dms }' % (name, round(t * 1000)), spss=1)
        # 루트는 트레이스마다 하나라 트레이스 수의 상한만 본다
        row['partial'] = len(roots or []) >= LIMIT
        if roots:
            slow = max(roots, key=lambda r: r.get('durationMs', 0))
            row['trace'] = {'id': slow['traceID'], 'ms': slow.get('durationMs')}
        spans = {}
        found = search('{ name = "%s" } >> { duration > %dms } | select(name)' % (name, round(t * 1000))) or []
        partial = truncated(found, LIMIT, SPSS)
        for tr in found:
            for ss in tr.get('spanSets', []):
                for sp in ss.get('spans', []):
                    n = next((a['value'].get('stringValue') for a in sp.get('attributes', []) if a['key'] == 'name'), sp.get('name')) or '-'
                    d = int(sp.get('durationNanos', 0)) / 1e9
                    e = spans.setdefault(n, {'durations': [], 'slow': (0, None)})
                    e['durations'].append(d)
                    if d > e['slow'][0]:
                        e['slow'] = (d, tr['traceID'])
        for n, e in sorted(spans.items(), key=lambda kv: -len(kv[1]['durations'])):
            methods.append({'api': name, 'method': n, 'threshold': t, 'count': len(e['durations']),
                            'p99': p99(e['durations']), 'trace': e['slow'][1], 'partial': partial})
    apis.append(row)

jobs = []
delay = by(q('histogram_quantile(0.99, sum by (relay, le) (increase(outbox_relay_publish_delay_seconds_bucket[%ds])))' % w), 'relay')
backlog = by(q('max_over_time((max by (relay) (outbox_relay_backlog))[%ds:5s])' % w), 'relay')
oldest = by(q('max_over_time((max by (relay) (outbox_relay_oldest_age_seconds))[%ds:5s])' % w), 'relay')
for name in sorted(set(delay) | set(backlog)):
    t, default = threshold('relay', name)
    v = delay.get(name)
    jobs.append({'kind': '아웃박스 릴레이', 'name': name, 'threshold': t, 'default': default, 'value': v, 'value_label': '발행 지연 p99',
                 'over': None if v is None or t is None else v > t,
                 'note': '미발행 최대 %s, 가장 오래 기다린 미발행 %s초' % (round(backlog.get(name, 0)), round(oldest.get(name, 0), 1))})
age = by(q('max_over_time((max by (worker) (async_worker_oldest_age_seconds))[%ds:5s])' % w), 'worker')
queue = by(q('max_over_time((sum by (worker) (async_worker_queue_size))[%ds:5s])' % w), 'worker')
for name in sorted(age):
    t, default = threshold('worker', name)
    v = age[name]
    jobs.append({'kind': '비동기 워커', 'name': name, 'threshold': t, 'default': default, 'value': v, 'value_label': '가장 오래 기다린 일',
                 'over': None if t is None else v > t, 'note': '대기열 최대 %s' % round(queue.get(name, 0))})
took = by(q('max_over_time((max by (scheduler) (batch_job_duration_seconds_max{scheduler!~"(%s)\\\\(\\\\)"}))[%ds:15s])' % (RELAY_JOBS, w)), 'scheduler')
for name in sorted(took):
    t, default = threshold('job', name)
    v = took[name]
    status = name in STATUS_JOBS
    # 작업 타이머의 최대는 시간이 지나면 0 으로 돌아온다. 구간 내내 0 이면 그 구간에 돌지 않은 것이다
    idle = v == 0
    jobs.append({'kind': 'cron 배치' if not status else '상태 확인', 'name': name, 'threshold': None if status else t,
                 'default': default, 'value': None if idle else v, 'value_label': '실행 한 번',
                 'over': None if status or idle or t is None else v > t,
                 'note': '판정하지 않는다' if status else ('구간에 실행이 없다' if idle else '')})
# 부하 시험 환경은 이 한 줄을 SSM 으로 돌려받아 출력 한도(24,000자) 안에 들어야 한다. 넘으면 메서드 행을 p99 가 작은 것부터 덜어 내고
# 덜어 낸 수를 남긴다. 잘린 JSON 은 읽지 못해 그 회차의 판정이 모두 "묻지 못함" 이 된다
OUT_LIMIT = 20000
doc = {'traced': bool(traced), 'tracing': tracing, 'limit': LIMIT, 'spss': SPSS, 'apis': apis, 'methods': methods, 'jobs': jobs, 'methods_cut': 0}
text = json.dumps(doc, ensure_ascii=False, separators=(',', ':'))
if len(text) > OUT_LIMIT:
    kept = sorted(methods, key=lambda m: -(m.get('p99') or 0))
    while kept and len(text) > OUT_LIMIT:
        # 한 번에 4분의 1 이상씩 덜어 다시 셈하는 횟수를 줄인다
        kept = kept[:min(len(kept) * 3 // 4, len(kept) - 1)]
        doc.update(methods=kept, methods_cut=len(methods) - len(kept))
        text = json.dumps(doc, ensure_ascii=False, separators=(',', ':'))
print(text)
PY

# 그 회차의 기준 초과를 묻는다. 로컬은 로컬 Prometheus 와 그라파나 프록시의 Tempo 를, 부하 시험 환경은 모니터링 서버에서 묻는다.
# 모니터링 서버는 Tempo 포트를 호스트에 열지 않아 컨테이너의 내부 주소로 묻는다. 트레이싱을 켜지 않은 환경이면 Tempo 가 없어 비운다
overrun() {
  local start="$1" end="$2"
  if [ -n "${LOCAL:-}" ]; then
    python3 -c "$OVERRUN_PY" "http://localhost:${PROMETHEUS_PORT:-19090}" \
      "http://localhost:${GRAFANA_PORT:-13000}/api/datasources/proxy/uid/tempo" admin:admin "$start" "$end"
    return
  fi
  remote "$(instance monitoring)" 300 "cat > /tmp/lt-overrun.py <<'PY'
$OVERRUN_PY
PY
ip=\$(docker inspect tempo --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' 2> /dev/null || true)
python3 /tmp/lt-overrun.py http://localhost:9090 \"\${ip:+http://\$ip:3200}\" '' $start $end"
}

#
# 결과 문서의 버전 절을 쓴다(#1326). 두 일을 한다.
#   collect <회차 폴더> <이름> <유지 시작 ms> <유지 끝 ms> <서버 지표 파일>  표준 입력의 핵심 수치와 서버 지표를 회차 폴더의 result.json 에 남긴다
#   render <결과 문서> <버전 폴더>                                         버전 폴더의 회차들(result.json)을 모두 읽어 결과 문서의 그 버전 절을 다시 쓴다
# 버전 절은 제목에 "회차별 결과" 가 든 절 안의 <!-- result:<버전 폴더 이름> --> 와 <!-- /result:<버전 폴더 이름> --> 사이다. 그 밖은 건드리지 않는다.
# 절이 없으면 3 으로 끝난다.
#
IFS= read -r -d '' RESULT_PY <<'PY' || true
import glob, json, os, re, sys

def parse(lines):
    # metrics 의 출력이다. Prometheus 는 "이름 [(꼬리표, 값), ...]", CloudWatch 는 "이름 값 값 ..." 이다
    out = {}
    for line in lines:
        line = line.rstrip('\n')
        if not line.strip() or ' ' not in line:
            continue
        name, rest = line.split(' ', 1)
        rest = rest.strip()
        if rest.startswith('['):
            # 값이 없는 분위수는 nan 으로 온다. literal_eval 은 nan 을 읽지 못해 그 이름만 풀어 준다
            try:
                out[name] = eval(rest, {'__builtins__': {}}, {'nan': float('nan'), 'NaN': float('nan'), 'inf': float('inf')})
            except (ValueError, SyntaxError, NameError, TypeError):
                continue
        else:
            nums = []
            for t in rest.split():
                try:
                    nums.append(float(t))
                except ValueError:
                    pass
            out[name] = [({}, n) for n in nums]
    return out

def kind(name):
    # 3회 묶음과 CloudWatch 의 여러 값을 하나로 줄이는 법이다. 크레딧과 최소는 가장 작은 값, 최대와 건수는 가장 큰 값, 나머지는 평균이다
    if 'Credit' in name or name.endswith('_min'):
        return 'min'
    if name.endswith(('_max', '_inc', '_max_pct')) or 'Exceeded' in name or 'CPUUtilization' in name:
        return 'max'
    return 'avg'

def reduce(values, how):
    values = [v for v in values if v is not None]
    if not values:
        return None
    if how == 'min':
        return min(values)
    if how == 'max':
        return max(values)
    return sum(values) / len(values)

def detail_value(name, rows):
    vals = [v for _, v in rows if v == v]
    if not vals:
        return None
    if len(vals) == 1:
        return vals[0]
    # 꼬리표로 나뉜 건수(5xx 를 uri 와 상태마다 센 것)와 CloudWatch 의 분마다 허용량 초과는 합친다. CloudWatch 의 나머지는 종류대로 줄인다
    if rows[0][0] or 'Exceeded' in name:
        return sum(vals)
    return reduce(vals, kind(name))

def k6_failed(path):
    # k6 요약의 유지 구간 실패 칸(http_req_failed{api:...,phase:hold})이다. passes 가 실패한 요청 수, passes + fails 가 요청 수다
    # 요약이 깨졌거나 꼴이 다르면 k6 의 표만 비운다. 핵심 수치와 상세는 그대로 남긴다
    out = {}
    try:
        metrics = json.load(open(path, encoding='utf-8')).get('metrics')
    except (OSError, ValueError, AttributeError):
        return out
    if not isinstance(metrics, dict):
        return out
    for key, v in metrics.items():
        m = re.fullmatch(r'http_req_failed\{(.*)\}', key)
        if not m or not isinstance(v, dict):
            continue
        tags = dict(t.split(':', 1) for t in m.group(1).split(',') if ':' in t)
        passes, fails = v.get('passes'), v.get('fails')
        if tags.get('phase') != 'hold' or not all(isinstance(n, int) and not isinstance(n, bool) and n >= 0 for n in (passes, fails)):
            continue
        total = passes + fails
        out[tags.get('api', '-')] = {'requests': total, 'failed': passes, 'rate': passes / total if total else None}
    return out

def read_overrun(path):
    # 기준 초과를 묻지 못했거나 깨졌으면 None 이다. 표는 "묻지 못함" 이 된다
    try:
        data = json.loads(open(path, encoding='utf-8').read().strip() or 'null')
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) and 'apis' in data else None

def collect(run_dir, name, hs, he, metrics_file, k6_file='', overrun_file=''):
    core = parse(sys.stdin)
    hold = (int(he) - int(hs)) / 1000
    apis = {}
    for key in ('core_count', 'core_5xx'):
        for labels, v in core.get(key, []):
            a = apis.setdefault(labels.get('uri', '-'), {'methods': set(), 'count': 0.0, 'errors': 0.0})
            a['methods'].add(labels.get('method', '-'))
            a['count' if key == 'core_count' else 'errors'] += v
    for q in ('p50', 'p95', 'p99'):
        for labels, v in core.get('core_' + q, []):
            if labels.get('uri') in apis and v == v:
                apis[labels['uri']][q] = v
    for uri, a in apis.items():
        a['methods'] = sorted(a['methods'])
        a['tps'] = a['count'] / hold if hold > 0 else None
        a['error_rate'] = a['errors'] / a['count'] if a['count'] else None
    detail = {}
    if metrics_file and os.path.exists(metrics_file):
        for n, rows in parse(open(metrics_file, encoding='utf-8')).items():
            detail[n] = detail_value(n, rows)
    os.makedirs(run_dir, exist_ok=True)
    # 다 쓴 뒤에 바꿔 끼운다. 쓰다 멈추면 앞의 result.json 이 그대로 남는다
    tmp = os.path.join(run_dir, 'result.json.tmp')
    json.dump({'name': name, 'hold': [int(hs), int(he)], 'core': {u: a for u, a in apis.items() if a['count'] > 0},
               'k6': k6_failed(k6_file) if k6_file else {}, 'overrun': read_overrun(overrun_file) if overrun_file else None,
               'detail': detail},
              open(tmp, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    os.replace(tmp, os.path.join(run_dir, 'result.json'))

def fmt_s(v):
    if v is None:
        return '-'
    if v < 1:
        return '%.1fms' % (v * 1000)
    if v < 60:
        return '%.2f초' % v
    # 1분 이상은 분과 시간으로 적는다. 28500.00초보다 7시간 55분이 읽힌다. 초는 반올림해 버린다
    m, sec = divmod(int(round(v)), 60)
    h, m = divmod(m, 60)
    return ' '.join(x for x in ('%d시간' % h if h else '', '%d분' % m if m else '', '%d초' % sec if sec else '') if x)

def fmt_n(v):
    if v is None:
        return '-'
    if abs(v - round(v)) < 1e-9:
        return '{:,}'.format(int(round(v)))
    return '{:,.3f}'.format(v).rstrip('0').rstrip('.')

def fmt_pct(v):
    return '-' if v is None else '%.2f%%' % (v * 100)

def trace_rows(run_dir):
    path = os.path.join(run_dir, 'traces.txt')
    if not os.path.exists(path):
        return None
    rows, notes = [], []
    for line in open(path, encoding='utf-8').read().splitlines()[1:]:
        cols = line.split('\t')
        if len(cols) < 7:
            # 고르지 못한 까닭을 캡처가 표 아래 글로 남긴다
            if line.strip():
                notes.append(line.strip())
            continue
        events = []
        logs = os.path.join(run_dir, cols[1], 'logs.txt')
        if os.path.exists(logs):
            for l in open(logs, encoding='utf-8'):
                if re.search(r'"level":"(WARN|ERROR)"', l):
                    for ev in re.findall(r'event=([A-Z][A-Z0-9_]+)', l):
                        if ev not in events:
                            events.append(ev)
        rows.append({'dir': cols[1], 'panel': cols[2], 'target': cols[3], 'value': cols[4], 'ms': float(cols[5] or 0), 'id': cols[6], 'events': events})
    return rows, notes

def judge(over):
    return {True: '**넘음**', False: '안 넘음', None: '-'}[over]

def basis(t, default):
    if t is None:
        return '지표 없음'
    # 이름별 값이 있으면 설정의 값(설계서에서 옮긴 값이거나 실행 인자로 바꾼 값)이고, 없으면 그 종류의 기본값이다
    return '%s(%s)' % (fmt_s(t), '기본값' if default else '설정')

def render_overrun(o):
    # 회차의 기준 초과 표다(#1339). 기준은 앱의 overrun_threshold_seconds 이고 값은 유지 구간으로 센다
    out = ['', '### 기준 초과(유지 구간)', '']
    if o is None:
        return out + ['기준 초과를 묻지 못했다']
    out += ['#### API', '', '| API | 기준 | p99 | 판정 | 가장 오래 걸린 트레이스 |', '|---|---|---|---|---|']
    for a in o['apis']:
        tr = '`%s` %s' % (a['trace']['id'], fmt_s((a['trace'].get('ms') or 0) / 1000)) if a.get('trace') else '-'
        if a.get('trace') and a.get('partial'):
            tr += '(일부에서 고름)'
        out.append('| %s | %s | %s | %s | %s |' % (a['name'], basis(a['threshold'], a['default']), fmt_s(a['p99']), judge(a['over']), tr))
    if not o['apis']:
        out.append('| 요청 없음 | - | - | - | - |')
    out += ['', '#### 요청 경로의 메서드', '']
    tracing = o.get('tracing') or ('ok' if o.get('traced') else 'no-tempo')
    if tracing == 'no-tempo':
        out.append('메서드: 추적을 켜지 않았다(Tempo 가 없다)')
    elif tracing == 'no-traces':
        out.append('메서드: Tempo 에 이 구간의 남은 트레이스가 없다. 앱에 추적을 붙이지 않았거나 샘플링에서 모두 빠졌다')
    elif not o['methods']:
        out.append('메서드: 그 API 의 기준을 넘은 자식 스팬이 없다')
    else:
        out += ['메서드는 그 API 아래에서 그 API 의 기준을 넘은 자식 스팬이다. 남은 트레이스(앞단 샘플링과 테일 샘플링을 지난 것)만 세어 넘은 수는 전체 요청의 수가 아니다. 우리 코드의 메서드에는 스팬이 없어 DB 쿼리, 외부 호출 같은 경계만 나온다',
                '', '| API | 메서드 | 기준 | 넘은 수 | p99 | 가장 오래 걸린 트레이스 |', '|---|---|---|---|---|---|']
        for m in o['methods']:
            count = fmt_n(m['count']) + (' 이상(일부만 셌다)' if m.get('partial') else '')
            out.append('| %s | `%s` | %s | %s | %s | `%s` |' % (m['api'], m['method'], fmt_s(m['threshold']), count, fmt_s(m['p99']), m['trace']))
        if o.get('methods_cut'):
            out += ['', '메서드 %s줄은 출력 한도 때문에 덜어 냈다. p99 가 큰 것부터 남겼다' % fmt_n(o['methods_cut'])]
        if any(m.get('partial') for m in o['methods']) or any(a.get('partial') for a in o['apis']):
            out += ['', '일부만 셌다: Tempo 검색은 트레이스 %s개, 트레이스마다 스팬 %s개까지만 돌려줘, 그 상한에 닿은 API 는 넘은 수와 p99, 가장 오래 걸린 트레이스가 그 일부에서 센 값이다' % (o.get('limit', 500), o.get('spss', 100))]
    # 릴레이와 워커는 늘 돌아 값이 있다. cron 은 하루 한 번 도는 것이 많아 구간에 실행이 없을 때가 많아 따로 표를 두고,
    # 실행 여부를 칸으로 적는다. 작업이 늘어도 표의 줄이 늘 뿐 글을 고치지 않는다
    crons = [j for j in o['jobs'] if j['kind'] in ('cron 배치', '상태 확인')]
    others = [j for j in o['jobs'] if j not in crons]
    if others:
        out += ['', '#### 릴레이와 워커', '', '| 작업 | 종류 | 기준 | 값 | 판정 | 참고 |', '|---|---|---|---|---|---|']
        for j in others:
            value = '%s %s' % (j['value_label'], fmt_s(j['value'])) if j['value'] is not None else '지표 없음'
            out.append('| `%s` | %s | %s | %s | %s | %s |' % (j['name'], j['kind'], basis(j['threshold'], j['default']), value, judge(j['over']), j['note'] or '-'))
    if crons:
        out += ['', '#### 기준을 넘은 cron', '', '| 작업 | 기준 | 구간에 실행 | 실행 한 번 | 판정 |', '|---|---|---|---|---|']
        # 구간에 돈 작업을 먼저 적는다
        for j in sorted(crons, key=lambda j: (j['value'] is None, j['name'])):
            ran = j['value'] is not None
            crit = '판정하지 않음(상태 확인)' if j['kind'] == '상태 확인' else basis(j['threshold'], j['default'])
            verdict = '-' if j['kind'] == '상태 확인' or not ran else judge(j['over'])
            out.append('| `%s` | %s | %s | %s | %s |' % (j['name'], crit, '있음' if ran else '없음', fmt_s(j['value']) if ran else '-', verdict))
    return out

def render(doc, version_dir):
    runs = []
    for p in sorted(glob.glob(os.path.join(version_dir, '*', 'result.json'))):
        r = json.load(open(p, encoding='utf-8'))
        if r['name'].endswith(('-warmup', '-profile')):
            continue
        r['dir'] = os.path.dirname(p)
        runs.append(r)
    runs.sort(key=lambda r: [int(x) if x.isdigit() else x for x in re.split(r'(\d+)', r['name'])])
    tag = os.path.basename(os.path.normpath(version_dir))
    rel = os.path.relpath(version_dir, os.path.dirname(os.path.abspath(doc)))
    version = re.sub(r'-run\d+$', '', runs[0]['name']) if runs else tag
    out = ['<!-- result:%s -->' % tag, '### %s' % version, '']
    out.append('`run` 이 회차마다 적는다(#1326). 핵심 수치는 앱이 잰 API 별 요청 시간으로 유지 구간만 센다. 오류율은 5xx 의 비율이다. 분위수는 같은 uri 의 메서드를 합쳐 센다.')
    out.append('3회 묶음은 핵심 수치와 상세의 평균, 시간, 초당 값은 평균이고, 이름이 `_max`, `_inc`(건수)로 끝나거나 CPU 최대, 허용량 초과인 값은 가장 큰 값, 크레딧과 `_min` 은 가장 작은 값이다.')
    out += ['', '#### 핵심 수치', '', '| API | 회차 | 초당 | p50 / p95 / p99 | 오류율 |', '|---|---|---|---|---|']
    uris = sorted({u for r in runs for u in r['core']})
    for u in uris:
        methods = sorted({m for r in runs if u in r['core'] for m in r['core'][u]['methods']})
        label = '%s %s' % (', '.join(methods), u)
        cells = []
        for r in runs:
            a = r['core'].get(u)
            if not a:
                continue
            cells.append(a)
            out.append('| %s | %s | %s | %s / %s / %s | %s |' % (label, r['name'], fmt_n(round(a['tps'], 1) if a['tps'] is not None else None),
                       fmt_s(a.get('p50')), fmt_s(a.get('p95')), fmt_s(a.get('p99')), fmt_pct(a['error_rate'])))
        if len(cells) > 1:
            avg = lambda k: reduce([c.get(k) for c in cells], 'avg')
            tps = avg('tps')
            out.append('| %s | **%d회 평균** | %s | %s / %s / %s | %s |' % (label, len(cells), fmt_n(round(tps, 1) if tps is not None else None),
                       fmt_s(avg('p50')), fmt_s(avg('p95')), fmt_s(avg('p99')), fmt_pct(avg('error_rate'))))
    if not uris:
        out.append('| 요청 없음 | - | - | - | - |')
    # 통과 기준의 오류율은 k6 가 본 실패로 판정한다(#1329). 시간 초과와 앱에 닿기 전의 실패가 든다
    out += ['', '#### k6 가 본 실패(유지 구간)', '', '통과 기준의 오류율은 이 실패율로 판정한다. 위의 오류율(앱의 5xx)에 들지 않는 시간 초과와 앱에 닿기 전의 실패가 든다.',
            '', '| API(k6 꼬리표) | 회차 | 요청 | 실패 | 실패율 |', '|---|---|---|---|---|']
    apis = sorted({a for r in runs for a in r.get('k6', {})})
    for a in apis:
        cells = []
        for r in runs:
            k = r.get('k6', {}).get(a)
            if not k:
                continue
            cells.append(k)
            out.append('| %s | %s | %s | %s | %s |' % (a, r['name'], fmt_n(k['requests']), fmt_n(k['failed']), fmt_pct(k['rate'])))
        if len(cells) > 1:
            out.append('| %s | **%d회 평균** | %s | %s | %s |' % (a, len(cells), fmt_n(round(reduce([c['requests'] for c in cells], 'avg'), 1)),
                       fmt_n(round(reduce([c['failed'] for c in cells], 'avg'), 1)), fmt_pct(reduce([c['rate'] for c in cells], 'avg'))))
    if not apis:
        out.append('| 요약 없음 | - | - | - | - |')
    names = []
    for r in runs:
        for n in r['detail']:
            if n not in names:
                names.append(n)
    # 지표의 뜻은 record_result 가 METRIC_NOTES 로 넘긴다(공통과 도메인). 없으면 비운다
    notes = dict(l.strip().split(' ', 1) for l in os.environ.get('METRIC_NOTES', '').splitlines() if ' ' in l.strip())
    out += ['', '#### 상세', '', '| 뜻 | 지표 | ' + ' | '.join(r['name'] for r in runs) + (' | %d회 묶음 |' % len(runs) if len(runs) > 1 else ' |'),
            '|---|---|' + '---|' * (len(runs) + (1 if len(runs) > 1 else 0))]
    for n in names:
        vals = [r['detail'].get(n) for r in runs]
        row = '| %s | `%s` | ' % (notes.get(n, '-'), n) + ' | '.join(fmt_n(v) for v in vals)
        if len(runs) > 1:
            row += ' | ' + fmt_n(reduce(vals, kind(n)))
        out.append(row + ' |')
    for r in runs:
        d = os.path.join(rel, os.path.basename(r['dir']))
        # 회차마다의 그림, 트레이스, 기준 초과는 접어 둔다. 회차 셋이면 버전 절이 300줄 남짓이 된다. 위의 요약 표는 펼쳐 둔다.
        # 접기 안의 Markdown 이 그려지려면 summary 뒤와 끝 앞에 빈 줄이 있어야 한다(GitHub, VS Code)
        out += ['', '<details><summary><b>%s</b>: 대시보드, 트레이스, 기준 초과</summary>' % r['name']]
        traces, notes = trace_rows(r['dir']) or (None, [])
        out += dashboard_section(r['dir'], d, r['name'], traces)
        out += ['', '### 트레이스']
        if traces is None:
            out += ['', '트레이스: 고른 목록(`traces.txt`)이 없다. 트레이싱을 켜지 않았거나 캡처가 트레이스를 찍지 않았다']
        elif not traces:
            out += ['', '트레이스: ' + (' '.join(notes) or '기준을 넘은 트레이스가 없다')]
        else:
            # 파일마다 칸을 둔다. 한 칸에 링크를 모으면 표가 좁을 때 링크마다 줄이 바뀌어 줄이 길어진다
            files = ('trace.png', 'logs.png', 'logs.txt')
            tail = ' 트레이스 ID | 트레이스 그림 | 로그 그림 | 로그 원문 | 경고와 오류 |'
            api, background = trace_groups(traces)
            for title, rows, head in (('기준을 넘은 API', api, '| 대상 | 판정 값 | 걸린 시간 |'),
                                      ('기준을 넘은 백그라운드 작업', background, '| 종류 | 대상 | 판정 값 | 걸린 시간 |')):
                if not rows:
                    continue
                out += ['', '#### %s' % title, '', '판정 값은 회차 구간의 최댓값이다.', '', head + tail, '|---' * (head.count('|') - 1 + 5) + '|']
                for t in rows:
                    cells = [file_link(r['dir'], d, t['dir'], f) for f in files]
                    lead = [t['kind']] if 'kind' in t else []
                    out.append('| ' + ' | '.join(lead + ['`%s`' % t['target'], t['value'], fmt_s(t['ms'] / 1000), '`%s`' % t['id']] + cells
                                                 + [', '.join('`%s`' % e for e in t['events']) or '-']) + ' |')
        out += render_overrun(r.get('overrun'))
        out += ['', '</details>']
    out.append('<!-- /result:%s -->' % tag)
    lines = open(doc, encoding='utf-8').read().split('\n')
    head = next((i for i, l in enumerate(lines) if l.startswith('#') and '회차별 결과' in l), None)
    if head is None:
        sys.exit(3)
    level = len(lines[head]) - len(lines[head].lstrip('#'))
    end = next((i for i in range(head + 1, len(lines)) if lines[i].startswith('#') and len(lines[i]) - len(lines[i].lstrip('#')) <= level), len(lines))
    start_mark, end_mark = '<!-- result:%s -->' % tag, '<!-- /result:%s -->' % tag
    s = next((i for i in range(head, end) if lines[i] == start_mark), None)
    e = next((i for i in range(head, end) if lines[i] == end_mark), None)
    if s is not None and e is not None and e > s:
        lines[s:e + 1] = out
    else:
        last = max([i for i in range(head, end) if lines[i].strip()])
        lines[last + 1:last + 1] = [''] + out
    open(doc, 'w', encoding='utf-8').write('\n'.join(lines))

def dashboard_section(run_dir, rel, name, traces):
    # 회차의 대시보드 절이다. 측정 회차와 진단 회차가 같이 쓴다. API 와 도메인 대시보드, 가장 느린 트레이스 그림을 펼치고 나머지는 분류마다의 표로 잇는다
    out, shown = ['', '### 대시보드', ''], []
    for pattern in ('dashboards/2._서비스/2._API.png', 'dashboards/3._도메인/*.png'):
        for f in sorted(glob.glob(os.path.join(run_dir, pattern))):
            f = os.path.relpath(f, run_dir)
            shown.append(f)
            out.append('![%s %s](%s)' % (name, os.path.splitext(os.path.basename(f))[0].replace('_', ' '), os.path.join(rel, f)))
    if traces:
        slow = max(traces, key=lambda t: t['ms'])
        f = os.path.join(slow['dir'], 'trace.png')
        if os.path.exists(os.path.join(run_dir, f)):
            shown.append(f)
            out.append('![%s 가장 느린 트레이스 %s](%s)' % (name, slow['id'], os.path.join(rel, f)))
    rest = dashboard_table(run_dir, rel, shown)
    if rest:
        out += ['', '#### 나머지 대시보드'] + rest
    return out

def dashboard_table(run_dir, rel, skip=()):
    # 회차 폴더의 대시보드를 분류(캡처의 폴더)마다 한 줄로 적는다. 한 줄로 이으면 열 장 남짓이 이어져 읽기 어렵다.
    # 폴더와 파일 이름 앞의 번호(1._)와 밑줄은 떼고 보인다
    plain = lambda n: re.sub(r'^\d+\._', '', n).replace('_', ' ')
    groups = {}
    for f in sorted(os.path.relpath(f, run_dir) for f in glob.glob(os.path.join(run_dir, 'dashboards', '**', '*.png'), recursive=True)):
        if f in skip:
            continue
        parts = f.split(os.sep)
        group = plain(parts[1]) if len(parts) > 2 else '-'
        groups.setdefault(group, []).append('[%s](%s)' % (plain(os.path.splitext(parts[-1])[0]), os.path.join(rel, f)))
    if not groups:
        return []
    return ['', '| 분류 | 대시보드 |', '|---|---|'] + ['| %s | %s |' % (g, ', '.join(links)) for g, links in groups.items()]

def trace_groups(traces):
    # 고른 트레이스를 기준을 넘은 API 와 백그라운드 작업으로 나눈다. 패널 이름은 표 머리가 대신하고, 백그라운드는 종류 칸에 남긴다.
    # 판정 값은 판정 패널(lc-infra 의 lc-api, lc-background)의 식을 회차 구간에서 훑은 최댓값이고 모든 패널이 초 단위다.
    # 대상은 꼬리표 이름(uri=, job_name= 등)을 떼고 값만 둔다
    api, background = [], []
    for t in traces:
        row = dict(t, target=re.sub(r'(?:^|(?<=\s))[\w.]+=', '', t['target']).strip())
        try:
            row['value'] = fmt_s(float(t['value']))
        except ValueError:
            pass
        title = t['panel']
        if title == '기준을 넘은 API':
            api.append(row)
            continue
        if '릴레이' in title:
            row['kind'] = '아웃박스 릴레이'
        elif '워커' in title:
            row['kind'] = '비동기 워커'
        elif 'cron' in title:
            row['kind'] = 'cron 배치, ' + ('실행 한 번' if '실행 한 번' in title else '시작 뒤 지난 시간')
        else:
            row['kind'] = title
        background.append(row)
    return api, background

def file_link(run_dir, rel, sub, name):
    # 트레이스 표의 파일 칸이다. 칸 머리가 파일의 이름이라 칸 안은 짧게 "보기" 로 잇고, 파일이 없으면 - 다
    return '[보기](%s)' % os.path.join(rel, sub, name) if os.path.exists(os.path.join(run_dir, sub, name)) else '-'

def longest_step(path):
    # 트레이스에서 루트를 뺀 스팬 가운데 가장 오래 걸린 것이다(#1341). trace.json 은 Tempo 의 원본(batches)이다
    try:
        data = json.load(open(path, encoding='utf-8'))
    except (OSError, ValueError):
        return None
    best = None
    for b in data.get('batches') or data.get('resourceSpans') or []:
        for ss in b.get('scopeSpans') or b.get('instrumentationLibrarySpans') or []:
            for sp in ss.get('spans', []):
                if not sp.get('parentSpanId'):
                    continue
                d = (int(sp.get('endTimeUnixNano', 0)) - int(sp.get('startTimeUnixNano', 0))) / 1e9
                if best is None or d > best[1]:
                    best = (sp.get('name', '-'), d)
    return best

# 자바 함수의 이름이다(TRIM_PY 가 패키지를 뗀 Class.method, 안쪽 클래스와 람다 포함). libc.so.6, poll, itable stub 같은 네이티브 프레임은 아니다
JAVA_FRAME = re.compile(r'^[A-Z][\w$]*\.[\w$<>]+$')

def java_leaf(stack):
    # 맨 끝에서부터 처음 만나는 자바 함수다. 네이티브 프레임의 몫을 그것을 부른 자바 함수에 붙이려는 것이다
    frames = stack.split(';')
    return next((f for f in reversed(frames) if JAVA_FRAME.match(f)), frames[-1])

def load_profile(archive):
    # 프로파일 파일을 서비스와 종류마다 (스택, 표본 수) 목록으로 읽는다. 서비스가 나뉘기 전의 파일은 앱 하나다
    import tarfile
    try:
        tar = tarfile.open(archive)
    except (OSError, tarfile.TarError):
        return None
    try:
        return read_profile(tar)
    except (KeyError, ValueError, OSError, tarfile.TarError, AttributeError):
        # 안의 manifest.json 이나 묶음 파일이 없거나 깨졌다. 부른 쪽이 비교만 건너뛴다
        return None

def read_profile(tar):
    with tar:
        manifest = json.load(tar.extractfile('manifest.json'))
        layout = ({svc: (m['kinds'], svc + '/') for svc, m in manifest['services'].items()} if 'services' in manifest
                  else {'lunchcatch': (manifest['kinds'], '')})
        out = {}
        for svc, (kinds, prefix) in layout.items():
            out[svc] = {}
            for kind, meta in kinds.items():
                rows = []
                for b in meta['batches']:
                    for line in tar.extractfile('%s%s/%d.folded' % (prefix, kind, b['at'])).read().decode().splitlines():
                        stack, _, n = line.rpartition(' ')
                        rows.append((stack, int(n)))
                out[svc][kind] = rows
        return out

def shares(rows, key):
    # 표본을 key(스택 -> 함수)로 모은 몫이다
    total = sum(n for _, n in rows)
    own = {}
    for stack, n in rows:
        k = key(stack)
        own[k] = own.get(k, 0) + n
    return {k: c / total for k, c in own.items()} if total else {}

def inclusive(rows, function):
    # 그 함수가 든 스택의 몫이다. Call Tree 의 Total 과 같다
    total = sum(n for _, n in rows)
    hit = sum(n for stack, n in rows if function in stack.split(';'))
    return hit / total if total else None

def profile_self(archive, top=5):
    # 서비스와 종류마다 Self 의 몫이다(#1341)
    #   frame  그 함수가 스택의 맨 끝인 표본이다. 그라파나 Call Tree 의 Self 와 같다
    #   java   맨 끝에서부터 처음 만나는 자바 함수에 붙인다. 네이티브 프레임(poll 등)에서 기다린 몫이 그것을 부른 자바 함수로 간다
    prof = load_profile(archive)
    if prof is None:
        return None
    out = {}
    for svc, kinds in prof.items():
        by = {'frame': {k: shares(rows, lambda st: st.rsplit(';', 1)[-1]) for k, rows in kinds.items()},
              'java': {k: shares(rows, java_leaf) for k, rows in kinds.items()}}
        out[svc] = {}
        for basis, by_kind in by.items():
            picked = []
            for kind in ('wall', 'cpu'):
                for f, _ in sorted(by_kind.get(kind, {}).items(), key=lambda kv: -kv[1])[:top]:
                    if f not in picked:
                        picked.append(f)
            # 그 종류에 표본이 있으면 빠진 함수는 0% 다. 표본이 없는 종류만 '-'(None)로 둔다
            at = lambda kind, f: by_kind[kind].get(f, 0.0) if by_kind.get(kind) else None
            out[svc][basis] = [(f, at('wall', f), at('cpu', f)) for f in picked]
    return out

def fmt_delta(v):
    return '-' if v is None else '%+.2f%%p' % (v * 100)

def render_compare(run_dir, d, rel_of):
    # 앞 버전의 진단 회차와 견준다(#1343). 프로파일 파일로 세어 환경이 떠 있지 않아도 된다
    try:
        meta = json.load(open(os.path.join(run_dir, 'baseline.json'), encoding='utf-8'))
    except (OSError, ValueError):
        return []
    if not meta.get('dir'):
        return ['', '### 앞 버전과의 비교', '', '견줄 앞 버전이 없다(첫 버전)']
    base_dir = os.path.normpath(os.path.join(run_dir, meta['dir']))
    base = os.path.basename(base_dir)
    cur_arc = next(iter(glob.glob(os.path.join(run_dir, 'diag-*-profile.tar.gz'))), None)
    base_arc = next(iter(glob.glob(os.path.join(base_dir, 'diag-*-profile.tar.gz'))), None)
    if not base_arc or not cur_arc:
        return ['', '### 앞 버전과의 비교', '', '앞 버전 %s 의 진단 회차 폴더나 프로파일 파일이 없다' % base]
    before, after = load_profile(base_arc), load_profile(cur_arc)
    if before is None or after is None:
        bad = [a for a, p in ((base_arc, before), (cur_arc, after)) if p is None]
        return ['', '### 앞 버전과의 비교', '', '프로파일 파일을 읽지 못해 견주지 않았다: ' + ', '.join('`%s`' % rel_of(a) for a in bad)]
    out = ['', '### 앞 버전과의 비교', '', '%s 를 기준으로 견준다.' % base]
    # 두 회차의 조건이 다르면 몫의 차이에 조건의 차이가 섞인다
    unread = []
    def condition(path):
        c = {}
        try:
            c = json.load(open(os.path.join(path, 'condition.json'), encoding='utf-8'))
        except (OSError, ValueError):
            unread.append(os.path.basename(path))
        try:
            w = {l.split()[0]: int(l.split()[1]) for l in open(os.path.join(path, '구간.txt'), encoding='utf-8') if l.split() and l.split()[0] in ('from', 'to')}
            c['구간 길이'] = '%d초' % round((w['to'] - w['from']) / 1000)
        except (OSError, KeyError, ValueError, IndexError):
            pass
        return c
    cb, cc = condition(base_dir), condition(run_dir)
    diff = [(k, cb.get(k, '-'), cc.get(k, '-')) for k in sorted(set(cb) | set(cc)) if cb.get(k) != cc.get(k)]
    out.append('')
    # 조건 파일을 둘 다 읽었고 적힌 값이 모두 같을 때만 같다고 한다. 하나라도 못 읽었으면 견준 항목만 적는다
    if unread:
        out.append('조건 파일(`condition.json`)을 읽지 못한 회차: %s. 구간 길이만 견줬다%s' % (
            ', '.join('`%s`' % u for u in unread), '' if not diff else ': ' + ', '.join('%s %s → %s' % row for row in diff)))
    else:
        out.append('조건: 같다' if not diff else '조건이 다르다: ' + ', '.join('%s %s → %s' % row for row in diff))
    images = [f for f in ('diff/wall.png', 'diff/batch-wall.png') if os.path.exists(os.path.join(run_dir, f))]
    if images:
        out.append('')
        out += ['![Diff %s](%s)' % (f[5:-4], os.path.join(d, f)) for f in images]
        links = [f for f in ('diff/cpu.png', 'diff/batch-cpu.png') if os.path.exists(os.path.join(run_dir, f))]
        if links:
            out.append('Diff: ' + ', '.join('[%s](%s)' % (f[5:-4], os.path.join(d, f)) for f in links))
    else:
        import shlex
        out += ['', 'Diff 그림: 앞 버전이 이 환경의 Pyroscope 에 없어 찍지 않았다. lc-infra 에서 아래로 찍어 이 회차 폴더의 `diff/` 에 넣는다. `LC_BACKEND_DIR` 은 lc-backend 경로다', '',
                '```bash', './scripts/loadtest-diff.sh %s %s%s' % (rel_of(base_dir), rel_of(run_dir), ''.join(' --fixed %s' % shlex.quote(f) for f in meta.get('fixed', []))), '```']
    fixed = meta.get('fixed', [])
    # 후보 파일은 버전 이름을 앞에 단다(<버전>-fixed-candidates.txt). 진단 회차 이름에서 -run1-profile 을 떼면 버전이다
    cand_name = re.sub(r'-run\d+-profile$', '', os.path.basename(run_dir)) + '-fixed-candidates.txt'
    cand = os.path.join(os.path.dirname(run_dir), cand_name)
    note = '고친 함수: ' + (', '.join('`%s`' % f for f in fixed) if fixed else '넘기지 않았다')
    if os.path.exists(cand):
        n = len([l for l in open(cand, encoding='utf-8').read().splitlines() if l.strip()])
        note += '. 후보 %d개는 [%s](%s) 에 있다(앞 버전 커밋과의 git diff)' % (n, cand_name, os.path.join(os.path.dirname(d), cand_name))
    out += ['', note]
    for svc in [s for s in after if s in before]:
        out += ['', '`%s`' % svc]
        if fixed:
            out += ['', '고친 함수의 몫(그 함수 아래 전체, Call Tree 의 Total)', '', '| 함수 | wall 앞 → 이 버전 | cpu 앞 → 이 버전 |', '|---|---|---|']
            for f in fixed:
                cells = []
                for kind in ('wall', 'cpu'):
                    a, b = inclusive(before[svc].get(kind, []), f), inclusive(after[svc].get(kind, []), f)
                    cells.append('%s → %s(%s)' % (fmt_pct(a), fmt_pct(b), fmt_delta(None if a is None or b is None else b - a)))
                out.append('| `%s` | %s | %s |' % (f, cells[0], cells[1]))
        for kind in ('wall', 'cpu'):
            a = shares(before[svc].get(kind, []), java_leaf)
            b = shares(after[svc].get(kind, []), java_leaf)
            delta = {k: b.get(k, 0) - a.get(k, 0) for k in set(a) | set(b) if k not in fixed}
            # 늘어난 쪽에는 늘어난 함수만, 줄어든 쪽에는 줄어든 함수만 넣는다. 한쪽이 모자라면 빈 칸으로 둔다
            up = sorted([kv for kv in delta.items() if kv[1] > 0], key=lambda kv: -kv[1])[:5]
            down = sorted([kv for kv in delta.items() if kv[1] < 0], key=lambda kv: kv[1])[:5]
            out += ['', '%s 에서 Self 몫이 가장 많이 바뀐 함수(자바 함수 기준, 고친 함수 밖)' % kind, '']
            if not up and not down:
                out.append('바뀐 함수가 없다')
                continue
            out += ['| 늘어난 함수 | 앞 → 이 버전 | 줄어든 함수 | 앞 → 이 버전 |', '|---|---|---|---|']
            cell = lambda f, dv: ('`%s`' % f, '%s → %s(%s)' % (fmt_pct(a.get(f, 0)), fmt_pct(b.get(f, 0)), fmt_delta(dv))) if f else ('', '')
            for i in range(max(len(up), len(down))):
                u = cell(*up[i]) if i < len(up) else cell(None, None)
                dn = cell(*down[i]) if i < len(down) else cell(None, None)
                out.append('| %s | %s | %s | %s |' % (u + dn))
    gone = [s for s in before if s not in after]
    new = [s for s in after if s not in before]
    if gone or new:
        out += ['', '한쪽에만 있는 서비스: ' + ', '.join(['`%s`(앞 버전만)' % s for s in gone] + ['`%s`(이 버전만)' % s for s in new])]
    return out

def render_diag(doc, version_dir):
    # 진단 회차를 결과 문서에서 제목에 "진단" 이 든 절에 적는다(#1341). 버전 폴더의 -profile 회차를 모두 그린다
    tag = os.path.basename(os.path.normpath(version_dir))
    rel = os.path.relpath(version_dir, os.path.dirname(os.path.abspath(doc)))
    runs = sorted(d for d in glob.glob(os.path.join(version_dir, '*-profile')) if os.path.isdir(d))
    # loadtest-diff.sh 에 넘길 경로다. 저장소의 결과 문서면 저장소 기준, 아니면 절대 경로다
    def rel_of(path):
        # 명령을 그대로 붙여 넣어 돌게 저장소 안의 경로는 "$LC_BACKEND_DIR"/<상대 경로> 로, 그 밖은 절대 경로로 쓰고 셸 인용을 붙인다
        import shlex
        root = os.environ.get('LOADTEST_ROOT', '')
        path = os.path.abspath(path)
        if root and path.startswith(root + os.sep):
            return '"$LC_BACKEND_DIR"/' + shlex.quote(os.path.relpath(path, root))
        return shlex.quote(path)
    version = re.sub(r'-run\d+-profile$', '', os.path.basename(runs[0])) if runs else tag
    out = ['<!-- diag:%s -->' % tag, '### %s' % version, '',
           '`run` 이 진단 회차마다 적는다(#1341). 진단 회차의 수치에는 프로파일링의 비용이 섞여 측정 회차와 견주지 않는다.']
    for run_dir in runs:
        name = os.path.basename(run_dir)
        d = os.path.join(rel, name)
        # 프로파일링이 실패한 회차(#1345)는 끝난 진단으로 그리지 않고 한 줄만 남긴다
        if os.path.exists(os.path.join(run_dir, 'profile-failed.txt')):
            out += ['', '**%s**: 프로파일링이 실패해 진단으로 적지 않았다. 캡처는 회차 폴더에 남아 있다' % name]
            continue
        # 측정 회차와 같게 회차를 접고 대시보드, 트레이스를 먼저 둔다. 그 뒤에 진단에만 있는 Call Tree, Self, 앞 버전과의 비교, 파일을 잇는다
        out += ['', '<details><summary><b>%s</b>: 대시보드, 트레이스, Call Tree, 프로파일, 앞 버전과의 비교</summary>' % name]
        traces, notes = trace_rows(run_dir) or (None, [])
        out += dashboard_section(run_dir, d, name, traces)
        out += ['', '### 트레이스']
        if traces is None:
            out += ['', '트레이스: 고른 목록(`traces.txt`)이 없다']
        elif not traces:
            out += ['', '트레이스: ' + (' '.join(notes) or '기준을 넘은 트레이스가 없다')]
        else:
            files = ('trace.png', 'logs.png', 'logs.txt', 'trace.json')
            tail = ' 가장 오래 걸린 단계 | 트레이스 ID | 트레이스 그림 | 로그 그림 | 로그 원문 | 트레이스 원본 |'
            api, background = trace_groups(traces)
            for title, rows, head in (('기준을 넘은 API', api, '| 대상 | 판정 값 | 걸린 시간 |'),
                                      ('기준을 넘은 백그라운드 작업', background, '| 종류 | 대상 | 판정 값 | 걸린 시간 |')):
                if not rows:
                    continue
                out += ['', '#### %s' % title, '', '판정 값은 회차 구간의 최댓값이다.', '', head + tail, '|---' * (head.count('|') - 1 + 6) + '|']
                for t in rows:
                    step = longest_step(os.path.join(run_dir, t['dir'], 'trace.json'))
                    step = '`%s` %s' % (step[0], fmt_s(step[1])) if step else '-'
                    cells = [file_link(run_dir, d, t['dir'], f) for f in files]
                    lead = [t['kind']] if 'kind' in t else []
                    out.append('| ' + ' | '.join(lead + ['`%s`' % t['target'], t['value'], fmt_s(t['ms'] / 1000), step, '`%s`' % t['id']] + cells) + ' |')
        out += ['', '### Call Tree', '']
        for f in ('calltree/wall.png', 'calltree/batch-wall.png'):
            if os.path.exists(os.path.join(run_dir, f)):
                out.append('![%s %s](%s)' % (name, f.replace('calltree/', 'Call Tree ').replace('.png', ''), os.path.join(d, f)))
        archive = next(iter(glob.glob(os.path.join(run_dir, 'diag-*-profile.tar.gz'))), None)
        selfs = profile_self(archive) if archive else None
        out += ['', '### Self 가 큰 함수']
        if not selfs:
            out += ['', '프로파일: 파일이 없다']
        for svc, bases in (selfs or {}).items():
            out += ['', '`%s`: wall 과 cpu 의 상위 5개를 합쳤다. wall 이 크고 cpu 가 작으면 기다린 시간, 둘 다 크면 계산이다' % svc]
            for basis, title in (('frame', '맨 끝 프레임 기준(그라파나 Call Tree 의 Self 와 같다)'),
                                 ('java', '자바 함수 기준(네이티브 프레임의 몫을 그것을 부른 가장 가까운 자바 함수에 붙였다)')):
                out += ['', '#### %s' % title, '', '| 함수 | wall Self | cpu Self |', '|---|---|---|']
                for f, w, c in bases[basis]:
                    out.append('| `%s` | %s | %s |' % (f, fmt_pct(w), fmt_pct(c)))
        out += render_compare(run_dir, d, rel_of)
        # 회차 폴더의 나머지 파일이다. 무엇인지 한 칸에 적는다
        named = [('calltree/cpu.png', 'Call Tree cpu'), ('calltree/batch-cpu.png', '배치 Call Tree cpu'),
                 ('redis.txt', 'Redis 명령별 서버 처리 시간'), ('metrics.json.gz', '지표 원본(다시 보기용)'), ('logs.jsonl.gz', '경고와 오류 로그 원본(다시 보기용)')]
        named += [(os.path.relpath(f, run_dir), '프로파일 보관(다시 보기와 Diff 용)') for f in sorted(glob.glob(os.path.join(run_dir, 'diag-*-profile.tar.gz')))]
        rows = ['| [%s](%s) | %s |' % (f, os.path.join(d, f), what) for f, what in named if os.path.exists(os.path.join(run_dir, f))]
        if rows:
            out += ['', '### 파일', '', '| 파일 | 내용 |', '|---|---|'] + rows
        out += ['', '</details>']
    out.append('<!-- /diag:%s -->' % tag)
    lines = open(doc, encoding='utf-8').read().split('\n')
    head = next((i for i, l in enumerate(lines) if l.startswith('#') and '진단' in l), None)
    if head is None:
        sys.exit(3)
    level = len(lines[head]) - len(lines[head].lstrip('#'))
    end = next((i for i in range(head + 1, len(lines)) if lines[i].startswith('#') and len(lines[i]) - len(lines[i].lstrip('#')) <= level), len(lines))
    s = next((i for i in range(head, end) if lines[i] == out[0]), None)
    e = next((i for i in range(head, end) if lines[i] == out[-1]), None)
    if s is not None and e is not None and e > s:
        lines[s:e + 1] = out
    else:
        last = max([i for i in range(head, end) if lines[i].strip()])
        lines[last + 1:last + 1] = [''] + out
    open(doc, 'w', encoding='utf-8').write('\n'.join(lines))

if sys.argv[1] == 'collect':
    collect(*sys.argv[2:9])
elif sys.argv[1] == 'diag':
    render_diag(sys.argv[2], sys.argv[3])
else:
    render(sys.argv[2], sys.argv[3])
PY

#
# 유효한 측정 회차의 핵심 수치와 서버 지표를 회차 폴더에 남기고 결과 문서의 버전 절을 다시 쓴다(#1326). after_run 이 부른다.
# 회차 폴더는 SNAPSHOT_OUT 아래의 회차 이름이다. SNAPSHOT_OUT 이 없으면(버전 하나를 돌지 않고 run 만 부른 경우) 적을 자리가 없어 건너뛴다.
# 로컬은 결과 문서 대신 build/loadtest-results/ 의 사본에 적고 커밋하지 않는다. 부하 시험 환경은 결과 문서와 회차 폴더를 커밋한다.
#
record_result() {
  local name="$1" hs="$2" he="$3" metrics_file="$4" run_out="${5:-}" run_dir doc rc=0 core k6 f dirs=()
  [ -n "${SNAPSHOT_OUT:-}" ] || { echo "result: SNAPSHOT_OUT 이 없어 $name 을 결과 문서에 적지 않는다. version 으로 돌면 적는다"; return 0; }
  run_dir="$SNAPSHOT_OUT/$name"
  # 묻기가 끝까지 성공한 뒤에만 모은다. 중간에 실패한 출력으로 모으면 빠진 건수가 0 으로 들어간다
  core=$(mktemp); k6=$(mktemp); over=$(mktemp)
  # run 의 출력에 남은 k6 요약이다(#1329). 없으면(옛 run) k6 의 실패 표를 비운다
  [ -z "$run_out" ] || sed -n 's/^k6-summary //p' "$run_out" | head -1 > "$k6"
  # 기준 초과는 묻지 못해도 나머지는 적는다(#1339). 그 회차의 표는 "묻지 못함" 이 된다
  overrun "$hs" "$he" | tail -1 > "$over" || { echo "WARN: $name 의 기준 초과를 묻지 못했다" >&2; : > "$over"; }
  if ! printf '%s\n' "$CORE_METRICS" | prom_metrics "$hs" "$he" > "$core" \
    || ! python3 -c "$RESULT_PY" collect "$run_dir" "$name" "$hs" "$he" "$metrics_file" "$k6" "$over" < "$core"; then
    rm -f "$core" "$k6" "$over"
    echo "WARN: $name 의 핵심 수치를 모으지 못했다. 결과 문서에 적지 않았다" >&2
    return 0
  fi
  rm -f "$core" "$k6" "$over"
  doc="$ROOT/$RESULT_DOC"
  [ -z "${LOCAL:-}" ] || doc=$(local_doc)
  METRIC_NOTES="$COMMON_METRIC_NOTES${DOMAIN_METRIC_NOTES:-}" python3 -c "$RESULT_PY" render "$doc" "$SNAPSHOT_OUT" || rc=$?
  case "$rc" in
    0) echo "result $name -> $doc" ;;
    3) echo "WARN: $RESULT_DOC 에 제목에 '회차별 결과' 가 든 절이 없어 적지 않았다. 절의 머리말을 쓰고 다시 적는다" >&2; return 0 ;;
    *) echo "WARN: $RESULT_DOC 에 적지 못했다" >&2; return 0 ;;
  esac
  [ -z "${LOCAL:-}" ] || return 0
  if ! git -C "$ROOT" diff --cached --quiet; then
    echo "WARN: 스테이징된 다른 파일이 있어 커밋하지 않았다. $RESULT_DOC 와 $run_dir 에는 적어 두었다" >&2
    return 0
  fi
  # 버전 절은 그 버전의 회차를 모두 그린다. 앞 회차의 커밋을 건너뛰었으면 그 회차 폴더도 함께 담아 문서의 링크가 깨지지 않게 한다
  for f in "$SNAPSHOT_OUT"/*/result.json; do
    [ -f "$f" ] && dirs+=("$(dirname "$f")")
  done
  if ! git -C "$ROOT" add -- "$RESULT_DOC" "${dirs[@]}"; then
    echo "WARN: 결과를 스테이징하지 못했다. git -C $ROOT restore --staged -- $RESULT_DOC $SNAPSHOT_OUT 로 풀고 손으로 커밋한다" >&2
    return 0
  fi
  if ! git -C "$ROOT" commit -q -m "test: $name 의 핵심 수치와 캡처를 결과에 적는다 (#1326)" -- "$RESULT_DOC" "${dirs[@]}"; then
    echo "WARN: 결과를 커밋하지 못했다. git -C $ROOT restore --staged -- $RESULT_DOC $SNAPSHOT_OUT 로 풀고 손으로 커밋한다" >&2
    return 0
  fi
  echo "committed $(git -C "$ROOT" rev-parse --short HEAD) $RESULT_DOC ${dirs[*]}"
}

#
# 진단 회차의 폴더(대시보드, Call Tree, 트레이스, Redis 지표, 프로파일 파일)를 커밋한다(#1335). after_run 이 캡처가 성공한 뒤 부른다.
# 결과 문서에는 적지 않는 회차라 record_result 의 커밋을 거치지 않는다. 커밋하지 않으면 작업 트리를 지울 때 프로파일 파일도 사라진다.
# 측정 회차와 같은 규칙이다. 로컬과 SNAPSHOT_OUT 이 없는 회차는 하지 않고, 다른 파일이 스테이징돼 있으면 커밋하지 않는다.
#
#
# 앞 버전 커밋과 이 버전 커밋 사이에 고친 함수 후보를 "클래스.메서드" 로 낸다(#1343). 진단 회차의 Diff 와 비교 표가 그 함수까지 펼친다.
# Java 용 diff 규칙(*.java diff=java)을 주면 바뀐 조각마다 그 조각을 감싼 메서드가 조각 머리에 나온다. 그 머리에서 메서드 이름을 읽는다.
# 새로 만든 파일은 머리가 없어 더한 줄의 메서드 선언을 읽는다. 시험 코드(src/test)는 뺀다. 람다와 안쪽 클래스는 프로파일의 이름과 다를 수 있다
#
IFS= read -r -d '' FIXED_PY <<'PY' || true
import re, subprocess, sys
root, base, head = sys.argv[1:4]
# 메서드 선언의 머리다. 이름 앞의 낱말이 return, new 같은 것이면 호출이라 뺀다
DECL = re.compile(r'(?:^|[\s;{}])((?:[\w$<>\[\],.?]+\s+)+)(\w+)\s*\(')
NOT_TYPE = {'return', 'new', 'throw', 'else', 'case', 'yield', 'await', 'assert', '='}
SKIP = {'if', 'for', 'while', 'switch', 'catch', 'synchronized', 'super', 'this', 'try'}
# 패키지 이름을 바꾸면 모든 파일의 package, import 줄이 바뀐다. 그런 줄과 빈 줄, 주석만 바뀐 줄은 고친 것으로 치지 않는다
TRIVIAL = re.compile(r'^\s*(?:package\s|import\s|//|/\*|\*|$)')

def clean(lines):
    # 문자열, 문자, 주석을 지운 줄이다. 중괄호를 셀 때 그 안의 것을 세지 않는다
    out, block = [], False
    for l in lines:
        t, i = '', 0
        while i < len(l):
            if block:
                j = l.find('*/', i)
                if j < 0:
                    i = len(l)
                else:
                    block, i = False, j + 2
                continue
            c = l[i]
            if l.startswith('/*', i):
                block, i = True, i + 2
            elif l.startswith('//', i):
                break
            elif c in '"\'':
                j = i + 1
                while j < len(l) and l[j] != c:
                    j += 2 if l[j] == '\\' else 1
                t += c + c; i = j + 1
            else:
                t += c; i += 1
        out.append(t)
    return out

def ranges(text):
    # 메서드마다 (이름, 첫 줄, 끝 줄) 이다. 선언 뒤 ; 보다 { 가 먼저 오면 메서드다. 중괄호로 끝을 찾는다
    found, stack, depth, pending = [], [], 0, None
    for n, l in enumerate(clean(text.split('\n')), start=1):
        # 한 줄 메서드(int f() { return g(); })는 선언과 호출이 한 줄에 있다. 호출이 아닌 첫 선언으로 범위를 연다
        m = next((x for x in DECL.finditer(l) if x.group(2) not in SKIP and x.group(1).split()[-1] not in NOT_TYPE), None)
        if m:
            pending = (m.group(2), n, m.end())
        for k, c in enumerate(l):
            if pending and pending[1] == n and k < pending[2]:
                continue
            if c == ';' and pending:
                pending = None
            elif c == '{':
                if pending:
                    stack.append((pending[0], pending[1], depth))
                    pending = None
                depth += 1
            elif c == '}':
                depth -= 1
                if stack and stack[-1][2] == depth:
                    name, first, _ = stack.pop()
                    found.append((name, first, n))
    return found

def show(ref, path):
    r = subprocess.run(['git', '-C', root, 'show', '%s:%s' % (ref, path)], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else ''

def owner(spans, line):
    # 그 줄을 감싼 가장 안쪽 메서드다
    inside = [s for s in spans if s[1] <= line <= s[2]]
    return min(inside, key=lambda s: s[2] - s[1])[0] if inside else None

out, files = [], []
cur = None
for line in sys.stdin:
    line = line.rstrip('\n')
    if line.startswith('diff --git'):
        cur = {'old': None, 'new': None, 'hunks': []}
        files.append(cur)
    elif cur is None:
        continue
    elif line.startswith('--- '):
        cur['old'] = line[6:] if line.startswith('--- a/') else None
    elif line.startswith('+++ '):
        cur['new'] = line[6:] if line.startswith('+++ b/') else None
    elif line.startswith('@@'):
        m = re.match(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', line)
        cur['hunks'].append({'old': int(m.group(1)), 'new': int(m.group(3)), 'minus': [], 'plus': []})
    elif cur['hunks'] and line[:1] == '-':
        cur['hunks'][-1]['minus'].append(line[1:])
    elif cur['hunks'] and line[:1] == '+':
        cur['hunks'][-1]['plus'].append(line[1:])
for f in files:
    path = f['new'] or f['old']
    if not path or not path.endswith('.java') or '/src/main/' not in '/' + path:
        continue
    cls = path.rsplit('/', 1)[-1][:-5]
    old_spans = ranges(show(base, f['old'])) if f['old'] else []
    new_spans = ranges(show(head, f['new'])) if f['new'] else []
    for h in f['hunks']:
        hits = [owner(old_spans, h['old'] + i) for i, l in enumerate(h['minus']) if not TRIVIAL.match(l)]
        hits += [owner(new_spans, h['new'] + i) for i, l in enumerate(h['plus']) if not TRIVIAL.match(l)]
        for name in hits:
            if name and '%s.%s' % (cls, name) not in out:
                out.append('%s.%s' % (cls, name))
print('\n'.join(out))
PY

fixed_candidates() {
  local from="$1" to="$2"
  git -C "$ROOT" rev-parse -q --verify "$from^{commit}" > /dev/null && git -C "$ROOT" rev-parse -q --verify "$to^{commit}" > /dev/null \
    || return 1
  # 바뀐 줄 번호를 두 커밋의 메서드 범위와 대조한다. 조각 머리(diff=java)는 바뀐 줄보다 앞의 메서드를 가리킬 수 있어 쓰지 않는다
  # 이름만 바꾼 파일은 바뀐 줄만 보게 이름 바꿈을 찾는다. 파일이 많으면 git 이 찾기를 그만두고 새 파일로 보여 상한을 올린다
  git -C "$ROOT" -c diff.renameLimit=20000 diff --no-color -M -U0 "$from" "$to" -- '*.java' | python3 -c "$FIXED_PY" "$ROOT" "$from" "$to"
}

#
# 로컬이 적는 결과 문서 사본이다. 처음에는 원본을 복사하고, 그 뒤 원본에 새로 생긴 절(회차별 결과, 진단)은 사본 끝에 더한다.
# 사본은 회차가 쌓인 것이라 덮어쓰지 않는다. 절을 더하지 않으면 원본에 나중에 생긴 절이 사본에 없어 적지 못한다(#1341)
#
local_doc() {
  local doc
  doc="$(dirname "$SNAPSHOT_OUT")/$(basename "$RESULT_DOC")"
  [ -f "$doc" ] || cp "$ROOT/$RESULT_DOC" "$doc"
  python3 - "$ROOT/$RESULT_DOC" "$doc" <<'PY'
import sys
src, dst = (open(p, encoding='utf-8').read() for p in sys.argv[1:3])
lines = src.split('\n')
for word in ('회차별 결과', '진단'):
    if any(l.startswith('#') and word in l for l in dst.split('\n')):
        continue
    head = next((i for i, l in enumerate(lines) if l.startswith('#') and word in l), None)
    if head is None:
        continue
    level = len(lines[head]) - len(lines[head].lstrip('#'))
    end = next((i for i in range(head + 1, len(lines)) if lines[i].startswith('#') and len(lines[i]) - len(lines[i].lstrip('#')) <= level), len(lines))
    dst = dst.rstrip('\n') + '\n\n' + '\n'.join(lines[head:end]).rstrip('\n') + '\n'
open(sys.argv[2], 'w', encoding='utf-8').write(dst)
PY
  echo "$doc"
}

#
# 진단 회차를 결과 문서의 진단 절에 적는다(#1341). 로컬은 build/loadtest-results/ 의 결과 문서 사본에 적는다.
# 결과 문서에 제목에 "진단" 이 든 절이 없으면 적지 않고 안내만 한다
#
record_diag() {
  local name="$1" doc rc=0
  [ -n "${SNAPSHOT_OUT:-}" ] || return 0
  doc="$ROOT/$RESULT_DOC"
  [ -z "${LOCAL:-}" ] || doc=$(local_doc)
  LOADTEST_ROOT="$ROOT" python3 -c "$RESULT_PY" diag "$doc" "$SNAPSHOT_OUT" || rc=$?
  case "$rc" in
    0) echo "diag $name -> $doc" ;;
    3) echo "WARN: $RESULT_DOC 에 제목에 '진단' 이 든 절이 없어 적지 않았다" >&2 ;;
    *) echo "WARN: $name 의 진단을 $RESULT_DOC 에 적지 못했다" >&2 ;;
  esac
}

commit_diag() {
  local name="$1" run_dir
  [ -z "${LOCAL:-}" ] && [ -n "${SNAPSHOT_OUT:-}" ] || return 0
  run_dir="$SNAPSHOT_OUT/$name"
  [ -d "$run_dir" ] || return 0
  if ! git -C "$ROOT" diff --cached --quiet; then
    echo "WARN: 스테이징된 다른 파일이 있어 커밋하지 않았다. $run_dir 에는 남아 있다" >&2
    return 0
  fi
  # 진단 절을 적은 결과 문서도 함께 담는다(#1341)
  # 고친 함수 후보(#1343)도 버전 폴더에 있으면 함께 담는다
  local extra=()
  local cand="$SNAPSHOT_OUT/${name%-run1-profile}-fixed-candidates.txt"
  [ ! -f "$cand" ] || extra=("$cand")
  if ! git -C "$ROOT" add -- "$run_dir" "$RESULT_DOC" ${extra[@]+"${extra[@]}"} \
    || ! git -C "$ROOT" commit -q -m "test: $name 의 진단 캡처와 프로파일을 남기고 진단 절에 적는다 (#1335, #1341, #1343)" -- "$run_dir" "$RESULT_DOC" ${extra[@]+"${extra[@]}"}; then
    echo "WARN: 진단 회차를 커밋하지 못했다. git -C $ROOT restore --staged -- $run_dir $RESULT_DOC 로 풀고 손으로 커밋한다" >&2
    return 0
  fi
  echo "committed $(git -C "$ROOT" rev-parse --short HEAD) $run_dir"
}

# 유지 구간의 부하 생성기 1분 평균 CPU 가 이 값을 넘으면 무효다(광고 서빙 부하 시험 문서 2장과 7장)
LOADGEN_CPU_MAX_PCT=80
after_run() {
  local name="$1" out="$2" ws we hs he metrics_out invalid="" target=--lt snap_rc=0
  case "$name" in *-warmup) return 0 ;; esac
  read -r _ ws we < <(grep -m1 '^window ' "$out" || echo "window 0 0")
  read -r _ hs he < <(grep -m1 '^hold ' "$out" || echo "hold 0 0")
  [ "$ws" -gt 0 ] && [ "$hs" -gt 0 ] || { echo "INVALID $name: run 의 출력에 window 나 hold 줄이 없다"; return 3; }
  echo "metrics $name window $ws $we"
  metrics_out=$(mktemp)
  metrics "$ws" "$we" | tee "$metrics_out" || echo "WARN: $name 의 서버 지표를 다 모으지 못했다" >&2
  echo "snapshot $name $ws ~ $he"
  [ -z "${LOCAL:-}" ] || target=--local
  #
  # 진단 회차(-profile)는 Call Tree 까지 찍고 무효를 판정하지 않는다. 표본을 뜨는 비용이 들어 결과에 쓰지 않는 회차다(#1306).
  # 진단 회차도 측정 회차와 같은 대시보드를 찍어 --domain 을 준다(#1335). 원인을 볼 때 같은 회차의 서버 자원, RDB, 캐시를 견준다.
  # 그 화면에는 표본을 뜨는 비용이 섞여 측정 회차와 견주지 않는다.
  #
  case "$name" in
    *-profile)
      #
      # 앞 버전의 진단 회차가 있으면 Diff 를 같이 찍는다(#1343). 그 회차가 이 Pyroscope 에 남아 있을 때만 그림이 나오고,
      # 없으면 캡처가 건너뛰어 진단 절에 loadtest-diff.sh 로 찍는 명령을 적는다. 비교 표는 프로파일 파일로 늘 센다
      #
      local diff_args=() f bfrom bto
      if [ -n "${DIAG_BASELINE_DIR:-}" ] && [ -f "$DIAG_BASELINE_DIR/구간.txt" ]; then
        bfrom=$(awk '$1 == "from" {print $2}' "$DIAG_BASELINE_DIR/구간.txt"); bto=$(awk '$1 == "to" {print $2}' "$DIAG_BASELINE_DIR/구간.txt")
        diff_args=(--baseline "$(basename "$DIAG_BASELINE_DIR")" --baseline-from "$bfrom" --baseline-to "$bto")
        while IFS= read -r f; do [ -z "$f" ] || diff_args+=(--fixed "$f"); done <<< "${DIAG_FIXED:-}"
      fi
      "$LC_INFRA_DIR/scripts/loadtest-snapshot.sh" "$target" --diag "$name" --from "$ws" --to "$he" \
        ${DOMAIN_SNAPSHOT:+--domain "$DOMAIN_SNAPSHOT"} ${SNAPSHOT_OUT:+--out "$SNAPSHOT_OUT"} ${diff_args[@]+"${diff_args[@]}"} || snap_rc=$?
      # 두 회차의 조건과 견준 앞 회차를 남긴다. 진단 절의 비교가 읽는다
      if [ -n "${SNAPSHOT_OUT:-}" ] && [ -d "$SNAPSHOT_OUT/$name" ]; then
        printf '%s\n' "${DIAG_CONDITION:-{\}}" > "$SNAPSHOT_OUT/$name/condition.json"
        python3 -c 'import json, os, sys; print(json.dumps({"dir": os.path.relpath(sys.argv[1], sys.argv[2]) if sys.argv[1] else "", "fixed": [f for f in sys.argv[3].splitlines() if f]}, ensure_ascii=False))' \
          "${DIAG_BASELINE_DIR:-}" "$SNAPSHOT_OUT/$name" "${DIAG_FIXED:-}" > "$SNAPSHOT_OUT/$name/baseline.json"
      fi
      rm -f "$metrics_out"
      # 프로파일링이 실패했으면 캡처의 성공과 상관없이 먼저 표식을 남긴다. 뒤 회차의 진단 절이 이 회차를 끝난 진단으로 그리지 않게 한다
      if [ -n "${PROFILE_FAILED:-}" ] && [ -n "${SNAPSHOT_OUT:-}" ]; then
        mkdir -p "$SNAPSHOT_OUT/$name"
        echo "프로파일링이 실패한 진단 회차다(#1345). 묶음이 빠졌을 수 있어 진단 절에 적지 않는다" > "$SNAPSHOT_OUT/$name/profile-failed.txt"
      fi
      [ "$snap_rc" -eq 0 ] || { echo "SNAPSHOT-FAILED $name: 캡처가 $snap_rc 로 끝났다. 환경을 내리기 전에 같은 구간(--from $ws --to $he)으로 다시 찍는다"; return 4; }
      echo "captured $name"
      # 프로파일링이 실패한 진단은 묶음이 빠졌을 수 있다. 캡처는 분석용으로 남기고 진단 절과 커밋은 건너뛴다. 실패는 run 이 5 로 낸다
      # 성공했으면 앞 시도의 실패 표식을 지운다. 실패 표식은 캡처 실패로 돌아가기 전에 이미 남겼다
      [ -n "${PROFILE_FAILED:-}" ] || [ -z "${SNAPSHOT_OUT:-}" ] || rm -f "$SNAPSHOT_OUT/$name/profile-failed.txt"
      if [ -n "${PROFILE_FAILED:-}" ]; then
        echo "WARN: $name 의 프로파일링이 실패해 진단 절에 적지 않고 커밋하지 않았다.${SNAPSHOT_OUT:+ 캡처는 $SNAPSHOT_OUT/$name 에 있다}" >&2
        return 0
      fi
      record_diag "$name"
      commit_diag "$name"
      return 0 ;;
  esac
  "$LC_INFRA_DIR/scripts/loadtest-snapshot.sh" "$target" --label "$name" --from "$ws" --to "$he" \
    ${DOMAIN_SNAPSHOT:+--domain "$DOMAIN_SNAPSHOT"} ${SNAPSHOT_OUT:+--out "$SNAPSHOT_OUT"} || snap_rc=$?
  # k6 가 목표 부하 구간 끝 전에 멈췄으면 유지 구간이 짧아 다른 회차와 견줄 수 없다
  [ "$we" -ge "$he" ] || invalid="$invalid
k6 가 목표 부하 구간 끝 전에 멈췄다: window 끝 $we < hold 끝 $he"
  invalid="$invalid$(loadgen_busy "$hs" "$he")$(cache_exhausted "$metrics_out")$(client_errors "$hs" "$he")"
  # 유효한 회차만 결과 문서에 적는다. 무효 회차와 캡처가 실패한 회차는 다시 잴 회차다
  [ -n "$invalid" ] || [ "$snap_rc" -ne 0 ] || record_result "$name" "$hs" "$he" "$metrics_out" "$out"
  rm -f "$metrics_out"
  # 캡처 실패는 무효 회차여도 알린다. 무효 회차도 원인을 보려면 그림이 있어야 한다
  [ "$snap_rc" -eq 0 ] || echo "SNAPSHOT-FAILED $name: 캡처가 $snap_rc 로 끝났다. 환경을 내리기 전에 같은 구간(--from $ws --to $he)으로 다시 찍는다"
  if [ -n "$invalid" ]; then
    echo "INVALID $name$invalid"
    return 3
  fi
  [ "$snap_rc" -eq 0 ] || return 4
  echo "valid $name"
}

# 유지 구간의 부하 생성기 CPU 를 따로 묻는다. metrics 는 램프업이 든 window 구간이라 문서의 기준(유지 구간)과 다르다. 부하 생성기 지표가 없으면(로컬) 판정하지 않는다
loadgen_busy() {
  local pct
  pct=$(printf '%s\n' "$COMMON_METRICS" | grep '^loadgen_cpu_max_pct ' | prom_metrics "$1" "$2" | python3 -c 'import sys, ast
rows = [ast.literal_eval(l.split(" ", 1)[1]) for l in sys.stdin if l.startswith("loadgen_cpu_max_pct ")]
print(rows[0][0][1] if rows and rows[0] else "")') || pct=""
  if [ -z "$pct" ]; then
    # 로컬은 부하 생성기가 없어 비는 것이 맞다. 부하 시험 환경에서 비면 이 조건을 판정하지 못한 것이라 알린다. 표준 출력은 무효 사유로 모이므로 표준 오류에 쓴다
    [ -n "${LOCAL:-}" ] || echo "WARN: 부하 생성기 CPU 를 읽지 못해 그 무효 조건을 판정하지 않았다" >&2
    return 0
  fi
  python3 -c "import sys; sys.exit(0 if float('$pct') <= $LOADGEN_CPU_MAX_PCT else 1)" \
    || printf '\n부하 생성기 CPU 가 유지 구간에서 %s%% 를 넘었다: %s%%' "$LOADGEN_CPU_MAX_PCT" "$pct"
}

# ElastiCache 의 CPU 크레딧이 바닥났거나 네트워크 허용량을 넘긴 패킷이 있었는지 metrics 의 출력에서 본다. 로컬은 ElastiCache 가 없다
#
# 유지 구간에서 k6 가 받은 4xx 응답이 전체 요청의 이 비율(%)을 넘으면 무효다(#1367). 회원 대역을 잘못 주는 것 같은 시험 설정의 오류다.
# 정상 회차는 0 이다. 요청 제한(429)도 나지 않게 회원 수를 잡았다. 5xx 와 시간 초과는 그 버전의 결과라 결과 문서의 실패율로 판정한다(#1329).
# 1% 는 작성자가 2026-11-26 대화에서 정했다
#
CLIENT_ERROR_MAX_PCT=1
client_errors() {
  local out
  # increase 는 구간 안의 첫 표본을 기준값으로만 써, 구간에 처음 생긴 계열의 첫 누적값을 빠뜨린다.
  # 유지 구간의 계열(phase="hold")은 유지 구간이 시작될 때 생겨 누적값이 그 구간의 건수다. 그래서 구간 안의 가장 큰 누적값으로 센다
  out=$(printf '%s\n' 'k6_4xx sum by (status) (max_over_time(k6_http_reqs_total{phase="hold",status=~"4.."}[$W]))' \
      'k6_all sum(max_over_time(k6_http_reqs_total{phase="hold"}[$W]))' | prom_metrics "$1" "$2" | python3 -c 'import sys, ast
rows = dict(l.split(" ", 1) for l in sys.stdin.read().splitlines() if " " in l)
total = ast.literal_eval(rows.get("k6_all", "[]"))
errors = ast.literal_eval(rows.get("k6_4xx", "[]"))
if not total or not total[0][1]:
    print("NA")
else:
    n = sum(v for _, v in errors)
    pct = 100 * n / total[0][1]
    # 넘었는지는 반올림하지 않은 값으로 가린다. 둘째 자리는 출력에만 쓴다
    codes = ",".join("%s:%d" % (m.get("status"), v) for m, v in errors if v) or "-"
    print("%.2f %d %d %s %d" % (pct, n, total[0][1], codes, pct > float(sys.argv[1])))' "$CLIENT_ERROR_MAX_PCT") || out=NA
  if [ "$out" = NA ]; then
    # 표준 출력은 무효 사유로 모이므로 표준 오류에 쓴다
    echo "WARN: 유지 구간의 k6 요청 수를 읽지 못해 4xx 의 무효 조건을 판정하지 않았다" >&2
    return 0
  fi
  read -r pct n total codes over <<< "$out"
  [ "$over" != 1 ] \
    || printf '\n유지 구간의 4xx 응답이 %s%% 를 넘었다: %s%% (%s / %s, %s). 회원 대역과 시드를 본다' "$CLIENT_ERROR_MAX_PCT" "$pct" "$n" "$total" "$codes"
}

cache_exhausted() {
  python3 - "$1" <<'PY'
import sys
for line in open(sys.argv[1]):
    parts = line.split()
    if not parts or not parts[0].startswith("valkey_"):
        continue
    values = [float(v) for v in parts[1:] if v.replace(".", "", 1).isdigit()]
    if parts[0].endswith("_CPUCreditBalance") and values and min(values) <= 0:
        print(f"\nElastiCache CPU 크레딧이 바닥났다: {parts[0]} 최소 {min(values)}", end="")
    if "AllowanceExceeded" in parts[0] and sum(values) > 0:
        print(f"\nElastiCache 네트워크 허용량을 넘겼다: {parts[0]} 합 {sum(values):g}", end="")
PY
}

#
# collapsed 스택을 그라파나에서 읽히게 다듬는다. 이 서비스의 코드가 일을 시작한 자리부터 남기고, 그 아래의 톰캣과 스프링,
# 스레드 풀 프레임을 잘라 그래프가 우리 코드에서 시작하게 한다. 시작 자리는 셋이다(#1127).
#   요청: 우리 컨트롤러
#   @Scheduled 작업: 스프링의 ScheduledMethodRunnable 이 부른 우리 메서드(실행을 감싼 SchedulerLoggingAspect 는 건너뛴다)
#   워커: 톰캣과 스케줄러가 아닌 스레드에서 처음 나오는 우리 프레임
# 쉬는 스레드는 버린다. 요청이 없는 톰캣 스레드, 다음 실행을 기다리는 스케줄러, 대기열에서 다음 일을 기다리는 워커다.
# 프레임 이름은 패키지를 뺀 클래스 이름(FeedController.getFeed)으로 줄인다. jfrconv 가 붙이는 프레임 종류 표시(_[j])도 뗀다.
# 호출마다 되풀이되는 스프링 프록시와 AOP 프레임도 뺀다. 다듬기 전에는 컨트롤러까지 프레임이 100개 넘게 쌓여 이름이 잘렸다.
# 람다 프레임(ServeLogBuffer$$Lambda.0x….run)도 뺀다. 이름에 주소가 들어 JVM 마다 달라져 같은 워커가 다른 이름으로 갈린다.
# 패키지는 두 철자를 모두 받는다. #1060 전의 커밋은 com.launchcatch 다.
#
IFS= read -r -d '' TRIM_PY <<'PY' || true
import re, sys
from collections import Counter
OURS = re.compile(r'^com[/.]la?unchcatch[/.]')
CONTROLLER = re.compile(r'^com[/.]la?unchcatch[/.].*Controller\.')
SCHEDULED = re.compile(r'ScheduledMethodRunnable\.')
ASPECT = re.compile(r'SchedulerLoggingAspect\.')
REQUEST_THREAD = re.compile(r'^org[/.]apache[/.](catalina|tomcat|coyote)[/.]')
IDLE = re.compile(r'BlockingQueue\.(poll|take)$|DelayedWorkQueue\.(poll|take)$')
KIND = re.compile(r'_\[[^\]]*\]$')
PROXY = re.compile(r'\$\$SpringCGLIB\$\$|^\$Proxy\d*\.|^jdk\.proxy|^CglibAopProxy|^ReflectiveMethodInvocation\.'
                   r'|^MethodProxy\.|^AopUtils\.|^Invokers\$Holder\.|^DirectMethodHandle\$Holder\.|^LambdaForm\$'
                   r'|^DirectMethodHandleAccessor\.|^Method\.invoke$|^ExposeInvocationInterceptor\.'
                   r'|\$\$Lambda[./$]')

def short(frame):
    name = frame.rsplit('/', 1)[-1]
    if '/' not in frame and name.count('.') > 1:
        name = '.'.join(name.split('.')[-2:])
    return name

def start(frames):
    top = next((i for i, f in enumerate(frames) if CONTROLLER.search(f)), None)
    if top is not None:
        return top
    if any(REQUEST_THREAD.search(f) for f in frames):
        return None
    runner = next((i for i, f in enumerate(frames) if SCHEDULED.search(f)), None)
    if runner is not None:
        return next((i for i in range(runner + 1, len(frames))
                     if OURS.search(frames[i]) and not ASPECT.search(frames[i])
                     and not PROXY.search(short(frames[i]))), None)
    return next((i for i, f in enumerate(frames) if OURS.search(f) and not PROXY.search(short(f))), None)

out = Counter()
for line in sys.stdin:
    stack, _, n = line.rstrip('\n').rpartition(' ')
    frames = [KIND.sub('', f) for f in stack.split(';')]
    top = start(frames)
    if top is None or any(IDLE.search(short(f)) for f in frames[top:]):
        continue
    kept = [f for f in (short(x) for x in frames[top:]) if not PROXY.search(f)]
    out[';'.join(kept)] += int(n)
for stack, n in out.items():
    print(stack, n)
PY

#
# 앱이나 배치 서버 하나에서 돈다. 인자는 회차 이름, 초, 모드, Pyroscope 주소, 앱 컨테이너, TRIM_PY 파일, async-profiler 판, 서비스 이름이다.
# 서비스 이름은 앱이 lunchcatch, 배치가 lunchcatch-batch 다. 트레이스의 서비스 이름과 같아 두 서버의 프로파일이 섞이지 않는다(#1234).
# 앱을 다시 띄우지 않고 async-profiler 를 컨테이너에 넣어 붙인다. 10초씩 떠서 다듬은 뒤 Pyroscope 로 보낸다.
# 컨테이너 안에서는 -e cpu 가 막혀(perf_event_paranoid=2) CPU 는 itimer 로 뜬다. 간격은 async-profiler 의 기본값이다.
# 고르는 일은 모두 TRIM_PY 가 한다. 요청, @Scheduled 작업, 워커의 스택을 함께 받아야 해서 프로파일러에서 거르지 않는다.
#
# both 는 cpu 와 wall 을 한 기록(JFR)으로 함께 뜨고 jfrconv 로 둘로 나눠 보낸다(#1127). 같은 시간, 같은 부하의 둘을 견줘
# 계산과 대기를 가른다. jfrconv 는 컨테이너 안의 자바로 돌아 JDK_JAVA_OPTIONS 를 읽는다. 트레이싱을 켠 동안
# 에이전트까지 붙어 느리게 뜨고 로그가 섞이므로 비우고 돌린다.
# 변환은 회차가 끝난 뒤에 한다(#1345). 회차 동안은 10초마다 JFR 만 떠 그 시각을 남기고, 끝나면 묶음마다 변환해 원래 시각으로 보낸다.
# 회차 도중에 그라파나에서 실시간으로 보는 것은 잃는다. 진단 회차는 끝난 뒤에 분석해 손해가 아니다
#
IFS= read -r -d '' PROFILE_SH <<'SH' || true
set -euo pipefail
run="$1"; secs="$2"; mode="$3"; pyro="$4"; app="$5"; trim="$6"; ver="$7"; svc="$8"
ap=/tmp/async-profiler/bin
work=$(mktemp -d)
if ! docker exec "$app" test -x "$ap/asprof"; then
  case "$(docker exec "$app" uname -m)" in aarch64) arch=arm64 ;; *) arch=x64 ;; esac
  curl -fsSL "https://github.com/async-profiler/async-profiler/releases/download/v$ver/async-profiler-$ver-linux-$arch.tar.gz" | tar -xz -C "$work"
  docker cp "$work/async-profiler-$ver-linux-$arch" "$app:/tmp/async-profiler" > /dev/null
fi
# 한 종류의 collapsed 를 다듬어 보낸다. 인자는 종류(cpu, wall), 파일, 시작과 끝 시각이다
send() {
  local kind="$1" raw="$2" from="$3" until="$4" rate
  case "$kind" in cpu) rate=100 ;; wall) rate=20 ;; esac
  python3 "$trim" < "$raw" > "$work/folded-$kind.txt"
  # 회차가 끝난 뒤의 조각처럼 우리 코드의 스택이 없으면 보내지 않는다
  [ -s "$work/folded-$kind.txt" ] || return 0
  curl -fsS -X POST --data-binary @"$work/folded-$kind.txt" \
    "$pyro/ingest?name=$svc.$kind%7Brun%3D$run%7D&from=$from&until=$until&format=folded&sampleRate=$rate&spyName=javaspy&units=samples&aggregationType=sum"
  echo "profile $svc $kind $from $(wc -l < "$work/folded-$kind.txt") stacks"
  # 보낸 묶음의 원래 구간이다(#1343). Pyroscope 는 묶음을 from 한 점에 찍고 until 은 돌려주지 않아, 다시 올릴 때 원래 until 을 쓰려고 남긴다
  echo "batch $svc $kind $from $until"
}
stop=$(( $(date +%s) + secs ))
while [ "$(date +%s)" -lt "$stop" ]; do
  from=$(date +%s)
  case "$mode" in
    cpu|wall)
      case "$mode" in cpu) ev=itimer; iv=10ms ;; wall) ev=wall; iv=50ms ;; esac
      out=$(docker exec "$app" "$ap/asprof" -d 10 -e "$ev" -i "$iv" -o collapsed -f /tmp/lt-profile.txt 1 2>&1) \
        || { echo "$out" >&2; exit 1; }
      docker cp "$app:/tmp/lt-profile.txt" "$work/raw.txt" > /dev/null
      send "$mode" "$work/raw.txt" "$from" "$(date +%s)"
      ;;
    both)
      # 이번 실행이 만든 JFR 은 끝날 때 지운다. 나누다 실패해 set -e 로 멈춰도 컨테이너의 /tmp 에 쌓이지 않는다
      # 뜨기 전에 경로를 적어, 뜨다 실패한 묶음의 파일도 지운다
      [ -n "${jfr_trap:-}" ] || { trap 'while read -r f; do docker exec "$app" rm -f "/tmp/lt-profile-$f.jfr" 2> /dev/null || true; done < "$work/jfr-files" 2> /dev/null' EXIT; jfr_trap=1; }
      echo "$from" >> "$work/jfr-files"
      # 회차 동안은 JFR 만 뜬다(#1345). 변환(jfrconv, 자바)은 묶음마다 앱 CPU 약 0.47초를 쓰고 그동안 프로파일러가 멈춰, 회차가 끝난 뒤로 미룬다
      out=$(docker exec "$app" "$ap/asprof" -d 10 -e itimer -i 10ms --wall 50ms -o jfr -f "/tmp/lt-profile-$from.jfr" 1 2>&1) \
        || { echo "$out" >&2; exit 1; }
      echo "$from $(date +%s)" >> "$work/jfr-batches"
      ;;
  esac
done
# 회차가 끝나면 모은 JFR 을 묶음마다 cpu 와 wall 로 나눠 원래 from, until 로 보낸다. 다 보낸 JFR 은 지운다
if [ -f "$work/jfr-batches" ]; then
  while read -r from until; do
    for kind in cpu wall; do
      docker exec "$app" env -u JDK_JAVA_OPTIONS "$ap/jfrconv" "--$kind" -o collapsed "/tmp/lt-profile-$from.jfr" "/tmp/lt-profile-$kind.txt" > /dev/null
      docker cp "$app:/tmp/lt-profile-$kind.txt" "$work/raw-$kind.txt" > /dev/null
      send "$kind" "$work/raw-$kind.txt" "$from" "$until"
    done
    docker exec "$app" rm -f "/tmp/lt-profile-$from.jfr"
  done < "$work/jfr-batches"
fi
SH

#
# 앱과 배치 서버마다 프로파일링을 붙여 그 시간 동안 Pyroscope 로 보낸다. 모드는 cpu, wall, both 다.
# 시험 환경은 서버 모두에 SSM 으로 함께 보내고 모니터링 서버의 Pyroscope(lunch-catch/lc-infra#71)로 보낸다.
#
# 배치 서버에도 붙인다(#1234). 노출 릴레이와 묶음 차감, 00:00 묶음, 재조회와 재예약 워커는 스케줄러가 켜진 배치 서버에서만 돈다.
# 고르지 않고 늘 둘 다 붙인다. 프로파일링 회차는 결과에 쓰지 않아 배치에 붙는 비용이 측정에 섞이지 않는다.
#
profile() {
  local name="${1:?회차 이름을 준다}" secs="${2:-60}" mode="${3:-wall}" pyro id ids role trim pids="" pid rc=0 outs
  case "$mode" in cpu|wall|both) ;; *) die "모드는 cpu, wall, both 다" ;; esac
  #
  # 보낸 묶음마다 원래 from 과 until 을 PROFILE_BATCHES 에 모은다(#1343). 프로파일 보관이 그 until 을 남겨 다시 올릴 때 원래 값을 쓴다.
  # 서버마다(앱과 배치)의 출력을 파일로 받아 batch 줄은 모으고 나머지는 지금처럼 보인다
  #
  outs=$(mktemp -d)
  if [ -n "${LOCAL:-}" ]; then
    trim=$(mktemp)
    printf '%s\n' "$TRIM_PY" > "$trim"
    for role in app batch; do
      # 서버마다 출력을 따로 받는다. 묶음 줄(batch)은 회차 폴더의 PROFILE_BATCHES 로 모은다(#1343)
      bash -c "$PROFILE_SH" profile "$name" "$secs" "$mode" "http://localhost:${PYROSCOPE_PORT:-14040}" \
        "$LOCAL_PROJECT-$role-1" "$trim" "$ASPROF_VERSION" "$(profile_service "$role")" > "$outs/local-$role.out" 2>&1 &
      pids="$pids $!"
    done
  else
    pyro="http://$(aws ec2 describe-instances --filters "Name=tag:Project,Values=$PROJECT" "Name=tag:Role,Values=monitoring" \
      "Name=instance-state-name,Values=running" --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text):4040"
    for role in app batch; do
      # 한 역할의 서버를 찾지 못하면 그 역할의 프로파일이 빠진 채 성공하지 않게 실패로 남기고 나머지는 이어서 뜬다
      ids=$(instances "$role") || { echo "profile $role 서버를 찾지 못했다" >&2; rc=1; continue; }
      [ -n "$ids" ] || { echo "profile 떠 있는 $role 서버가 없다" >&2; rc=1; continue; }
      for id in $ids; do
        # 배치 서버의 앱 컨테이너 이름도 $PROJECT 다
        remote "$id" $(( secs + 300 )) "set -o pipefail
cat > /tmp/lt-trim.py <<'PY'
$TRIM_PY
PY
cat > /tmp/lt-profile.sh <<'SH'
$PROFILE_SH
SH
# batch 줄은 모두, 나머지는 끝의 몇 줄만 돌려준다. SSM 출력 한도(24,000자)는 앞에서부터 남기므로 batch 줄의 수를 맨 앞에 낸다.
# 받는 쪽이 받은 줄 수와 견줘, 한도에 걸려 뒤가 잘렸으면 실패로 센다(회차 5분이면 서버마다 60줄 남짓, 약 3,000자)
bash /tmp/lt-profile.sh $name $secs $mode $pyro $PROJECT /tmp/lt-trim.py $ASPROF_VERSION $(profile_service "$role") > /tmp/lt-profile.out 2>&1
rc=\$?
echo \"batch-count \$(grep -c '^batch ' /tmp/lt-profile.out)\"
grep '^batch ' /tmp/lt-profile.out
echo batch-end
grep -v '^batch ' /tmp/lt-profile.out | tail -3
exit \$rc" > "$outs/$id.out" 2>&1 &
        pids="$pids $!"
      done
    done
  fi
  # 서버 하나라도 프로파일을 보내지 못했으면 실패로 끝난다
  for pid in $pids; do
    wait "$pid" || rc=1
  done
  keep_batches "$outs" || rc=1
  return "$rc"
}

# 서버마다의 프로파일링 출력에서 batch 줄은 PROFILE_BATCHES 에 더하고 나머지는 보인다
# 원격 출력은 맨 앞의 batch-count, 묶음 줄들, 끝 표식 batch-end 다. 표식이 없거나 온전한 줄의 수가 다르면
# 출력 한도에 걸려 잘린 것이라 1 로 끝난다. 마지막 줄이 중간에서 잘려도 온전한 꼴(서비스, 종류, from, until)이 아니라 세지 않는다
BATCH_LINE='^batch [^ ]+ [^ ]+ [0-9]+ [0-9]+$'
keep_batches() {
  local outs="$1" f want got rc=0 lines
  for f in "$outs"/*.out; do
    [ -f "$f" ] || continue
    want=$(sed -n 's/^batch-count //p' "$f" | head -1)
    if [ -n "$want" ]; then
      got=$(grep -Ec "$BATCH_LINE" "$f" || true)
      if [ "$want" != "$got" ] || ! grep -qx 'batch-end' "$f"; then
        echo "ERROR: $(basename "$f" .out) 의 묶음 줄을 $want 줄 가운데 온전한 $got 줄만 받았다(끝 표식 $(grep -qx 'batch-end' "$f" && echo 있음 || echo 없음)). SSM 출력 한도에 걸렸다. 회차를 줄여 다시 돈다" >&2
        rc=1
        # 이 서버의 줄은 믿지 못해 남기지 않는다. 다른 서버의 온전한 줄은 아래에서 그대로 남긴다
        continue
      fi
    fi
    # 서버마다 따로 남긴다. 한 서버가 잘려도 다른 서버의 묶음은 원래 from 과 until 로 남는다.
    # 묶음 줄이 없는 것(grep 이 1)은 실패가 아니다. 파일에 쓰지 못한 것은 원래 구간을 잃으므로 실패로 센다
    [ -n "${PROFILE_BATCHES:-}" ] || continue
    lines=$(grep -E "$BATCH_LINE" "$f" || true)
    if [ -n "$lines" ] && ! printf '%s\n' "$lines" >> "$PROFILE_BATCHES"; then
      echo "ERROR: 묶음 줄을 $PROFILE_BATCHES 에 쓰지 못했다" >&2
      rc=1
    fi
  done
  grep -hv '^batch \|^batch-count \|^batch-end$' "$outs"/*.out || true
  rm -rf "$outs"
  return "$rc"
}

# Pyroscope 의 서비스 이름이다. 트레이스의 서비스 이름과 같게 둔다
profile_service() {
  case "$1" in app) echo lunchcatch ;; batch) echo lunchcatch-batch ;; esac
}

#
# 앱의 compose 덮어쓰기 파일을 낸다. 인자는 트레이싱의 run 라벨, 에이전트 jar 경로, 연결 풀을 끌지(1)다.
# 시험 환경과 로컬 모두 앱 서비스가 app 이고 같은 compose 에 alloy 가 있어 같은 파일을 쓴다.
#
# 라벨을 주면 OpenTelemetry 에이전트를 붙인다(#1099).
# 운영 compose 가 JAVA_TOOL_OPTIONS 에 힙과 DNS 설정을 넣어 에이전트는 JDK_JAVA_OPTIONS 로 준다.
# 앱과 배치 모두 요청과 작업 실행의 10% 만 기록하고, 그 가운데 남길 트레이스는 Alloy 의 테일 샘플링이 고른다(lunch-catch/lc-infra#72, #195). 운영과 같다(#1311).
# 비율은 TRACE_SAMPLE_RATIO 로 바꾼다(운영과 같은 이름, 기본 0.1). 장애 주입 실험처럼 모두 남길 때 1.0 을 주고 switch 한다.
# 지표와 로그는 지금 길을 그대로 쓴다.
# 에이전트가 기본으로 켜는 계측을 모두 끄고 앱이 쓰는 경계의 계측만 켠다(#1186). 쓰지 않는 계측도 메서드 진입마다 일을 한다.
# 켜는 것: HTTP 받기(tomcat, servlet, spring-webmvc), DB(jdbc, hibernate, spring-data), 외부 HTTP(카카오와 토스의 WebClient: spring-webflux, reactor, reactor-netty),
# AWS(S3, Bedrock, Rekognition: aws-sdk), 스케줄 작업(spring-scheduling), 문맥 전파(executors), 로그의 trace_id(logback-mdc).
# 앱이 새 경계 라이브러리를 쓰면 이 목록에 더한다. 빠지면 그 경계가 트레이스에서 사라진다.
# Lettuce 는 켜지 않는다(#1185). 명령마다 스팬이라 피드 요청 하나의 스팬 가운데 가장 많다.
# Valkey 명령의 시간은 앱의 lettuce_command_completion_seconds(#1180)와 Redis 서버 지표로 보고, 트레이스에서는 이름 없는 빈 구간이 된다.
#
# 풀을 끄면 Valkey 연결 풀이 없던 때와 같이 돈다(#1127). 같은 커밋을 풀 켬과 끔으로 재 풀만의 효과를 가른다.
#
app_override() {
  printf 'services:\n'
  service_override app "$@"
}

# compose 덮어쓰기에서 서비스 한 칸이다. 첫 인자가 compose 의 서비스 이름이고 나머지는 app_override 와 같다
service_override() {
  local name="$1" label="$2" jar="$3" nopool="$4" extra="${5:-}" service="${6:-lunchcatch}" pair
  printf '  %s:\n' "$name"
  [ -z "$label" ] || printf '    volumes:\n      - %s:/opt/otel.jar:ro\n' "$jar"
  printf '    environment:\n'
  if [ -n "$label" ]; then
    cat <<EOF
      JDK_JAVA_OPTIONS: "-javaagent:/opt/otel.jar"
      OTEL_SERVICE_NAME: $service
      OTEL_RESOURCE_ATTRIBUTES: "run=$label"
      OTEL_EXPORTER_OTLP_ENDPOINT: http://alloy:4318
      OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
      OTEL_TRACES_SAMPLER: parentbased_traceidratio
      OTEL_TRACES_SAMPLER_ARG: "${TRACE_SAMPLE_RATIO:-0.1}"
      OTEL_METRICS_EXPORTER: none
      OTEL_LOGS_EXPORTER: none
      OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED: "false"
      OTEL_INSTRUMENTATION_TOMCAT_ENABLED: "true"
      OTEL_INSTRUMENTATION_SERVLET_ENABLED: "true"
      OTEL_INSTRUMENTATION_SPRING_WEBMVC_ENABLED: "true"
      OTEL_INSTRUMENTATION_JDBC_ENABLED: "true"
      OTEL_INSTRUMENTATION_HIBERNATE_ENABLED: "true"
      OTEL_INSTRUMENTATION_SPRING_DATA_ENABLED: "true"
      OTEL_INSTRUMENTATION_SPRING_WEBFLUX_ENABLED: "true"
      OTEL_INSTRUMENTATION_REACTOR_ENABLED: "true"
      OTEL_INSTRUMENTATION_REACTOR_NETTY_ENABLED: "true"
      OTEL_INSTRUMENTATION_AWS_SDK_ENABLED: "true"
      OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED: "true"
      OTEL_INSTRUMENTATION_EXECUTORS_ENABLED: "true"
      OTEL_INSTRUMENTATION_LOGBACK_MDC_ENABLED: "true"
EOF
  fi
  [ -z "$nopool" ] || printf '      SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED: "false"\n'
  # switch 의 --env 로 받은 설정 값이다. 다시 빌드하지 않고 조정 값을 바꿔 회차를 돈다(#1170)
  while IFS= read -r pair; do
    [ -n "$pair" ] || continue
    # compose 가 $ 를 변수로 바꾸지 않게 $$ 로 적는다
    printf '      %s: "%s"\n' "${pair%%=*}" "$(printf '%s' "${pair#*=}" | sed 's/[\\"]/\\&/g; s/\$/$$/g')"
  done <<< "$extra"
}

#
# 로컬 앱을 다시 띄운다. 이름을 주면 에이전트를 붙이고 주지 않으면 뗀다. 둘째 인자가 1 이면 연결 풀을 끈다.
# 이미지는 다시 만들지 않는다. 앱 코드를 고쳤으면 로컬 구성에서 docker compose up -d --build app 을 먼저 돌린다.
#
#
# 로컬의 앱과 배치를 한 덮어쓰기 파일에 함께 적는다(#1244). 인자는 이름, 에이전트 jar, 풀 끔, 둘에 같은 값, 앱만의 값, 배치만의 값이다.
# 배치는 lc-infra 의 compose.lt-like.yaml 이 따로 띄우는 batch 서비스이고, 트레이스의 서비스 이름은 부하 시험 환경처럼 lunchcatch-batch 다.
#
local_override() {
  printf 'services:\n'
  service_override app "$1" "$2" "$3" "${4:-}${5:-}" lunchcatch
  service_override batch "$1" "$2" "$3" "${4:-}${6:-}" lunchcatch-batch
}

local_switch() {
  local label="${1:-}" nopool="${2:-}" extra="${3:-}" app_extra="${4:-}" batch_extra="${5:-}" dir="$ROOT/build/otel" over="" i svc ready
  if [ -n "$label" ]; then
    mkdir -p "$dir"
    # 받다 끊긴 jar 가 남아 다음에 그대로 쓰이지 않게 다 받은 뒤에만 제 이름으로 옮긴다
    if [ ! -s "$dir/otel-$OTEL_VERSION.jar" ]; then
      curl -fsSL "$OTEL_JAR_URL" -o "$dir/otel-$OTEL_VERSION.jar.part"
      mv "$dir/otel-$OTEL_VERSION.jar.part" "$dir/otel-$OTEL_VERSION.jar"
    fi
  fi
  if [ -n "$label$nopool$extra$app_extra$batch_extra" ]; then
    mkdir -p "$dir"
    over="$dir/compose.override.yaml"
    local_override "$label" "$dir/otel-$OTEL_VERSION.jar" "$nopool" "$extra" "$app_extra" "$batch_extra" > "$over"
  fi
  #
  # 로컬은 관측 도구가 앱과 같은 Docker 가상 머신에 있다. 트레이싱 회차를 이어 돌리면 Alloy 와 Tempo 에 스팬과 트레이스가 쌓여
  # 앱과 메모리를 나눠 써, 설정을 바꿀 때마다 둘을 다시 띄워 비운다(#1267). 부하 시험 환경은 모니터링 서버가 따로라 하지 않는다.
  #
  docker restart "$LOCAL_PROJECT-alloy-1" "$LOCAL_PROJECT-tempo-1" > /dev/null
  # 부하 시험 환경처럼 배치를 따로 띄운다(#1244). 앱은 스케줄러를 끄고 배치가 batch 프로필로 작업을 돈다
  LC_BACKEND_DIR="$ROOT" docker compose -f "$LC_INFRA_DIR/observability/local/compose.yaml" \
    -f "$LC_INFRA_DIR/observability/local/compose.lt-like.yaml" ${over:+-f "$over"} \
    up -d --no-build app batch
  for svc in app batch; do
    local_ready "$svc"
    ready=$(docker exec "$LOCAL_PROJECT-$svc-1" curl -fs localhost:8081/actuator/health/readiness) \
      || die "$svc 가 5분 안에 준비되지 않았다"
    echo "$svc $ready"
    docker exec "$LOCAL_PROJECT-$svc-1" sh -c 'echo "JDK_JAVA_OPTIONS=${JDK_JAVA_OPTIONS:-}"'
    docker exec "$LOCAL_PROJECT-$svc-1" sh -c 'if [ -n "${SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED:-}" ]; then echo "SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED=$SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED"; else echo "pool on"; fi'
  done
}

