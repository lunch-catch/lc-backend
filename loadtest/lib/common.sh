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
    return [({k: v for k, v in x['metric'].items() if k in ('uri', 'status')}, round(float(x['value'][1]), 3)) for x in r]
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

# 초를 CloudWatch 가 받는 UTC 시각으로 바꾼다. 리눅스와 WSL 의 date 는 -d @초 를, macOS 의 date 는 -r 초 를 받는다(#1228)
utc_iso() {
  date -u -d "@$1" +%FT%TZ 2> /dev/null || date -u -r "$1" +%FT%TZ
}

# 회차 구간의 서버 지표를 모은다. 로컬은 로컬 Prometheus 만 묻는다. RDS 와 ElastiCache 가 없다
metrics() {
  local start="${1:?시작 ms 를 준다}" end="${2:?끝 ms 를 준다}" queries
  queries="$COMMON_METRICS${DOMAIN_METRICS:-}"
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
IFS= read -r -d '' SCRAPE_GAPS_PY <<'PY' || true
import json, sys, urllib.parse, urllib.request
base, s, e = sys.argv[1], int(sys.argv[2]) // 1000, int(sys.argv[3]) // 1000
expr = 'sum by (job) (count_over_time((up{job=~"app|batch"} == 0)[%ds:15s]))' % max(e - s, 15)
r = json.load(urllib.request.urlopen(base + '/api/v1/query?' + urllib.parse.urlencode({'query': expr, 'time': e})))
got = {x['metric'].get('job'): int(float(x['value'][1])) for x in r['data']['result']}
for job in ('app', 'batch'):
    print('수집', job.upper() + '_SCRAPE_MISSED', got.get(job, 0))
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
  case "$name" in *-warmup|*-profile|*-tracing) echo "warnings: $name 은 결과에 적지 않는다"; return 0 ;; esac
  # 회차 끝의 로그가 Alloy 를 거쳐 Loki 에 닿기를 기다린다
  sleep 10
  rows=$(warning_counts "$start" "$end") || { echo "WARN: 경고 로그를 묻지 못했다. 결과 문서에 적지 않았다" >&2; return 0; }
  echo "warnings"
  printf '%s\n' "${rows:-없음}"
  # 지표 수집이 빠진 칸 수도 같은 표에 적는다(#1222). 묻지 못하면 경고 로그만 적는다
  gaps=$(scrape_gaps "$start" "$end") || { echo "WARN: 지표 수집 실패 칸을 묻지 못했다. 경고 로그만 적는다" >&2; gaps=""; }
  echo "scrape gaps"
  printf '%s\n' "${gaps:-묻지 못함}"
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
# 앱 서버 하나에서 돈다. 인자는 회차 이름, 초, 모드, Pyroscope 주소, 앱 컨테이너, TRIM_PY 파일, async-profiler 판이다.
# 앱을 다시 띄우지 않고 async-profiler 를 컨테이너에 넣어 붙인다. 10초씩 떠서 다듬은 뒤 Pyroscope 로 보낸다.
# 컨테이너 안에서는 -e cpu 가 막혀(perf_event_paranoid=2) CPU 는 itimer 로 뜬다. 간격은 async-profiler 의 기본값이다.
# 고르는 일은 모두 TRIM_PY 가 한다. 요청, @Scheduled 작업, 워커의 스택을 함께 받아야 해서 프로파일러에서 거르지 않는다.
#
# both 는 cpu 와 wall 을 한 기록(JFR)으로 함께 뜨고 jfrconv 로 둘로 나눠 보낸다(#1127). 같은 시간, 같은 부하의 둘을 견줘
# 계산과 대기를 가른다. jfrconv 는 컨테이너 안의 자바로 돌아 JDK_JAVA_OPTIONS 를 읽는다. 트레이싱을 켠 동안
# 에이전트까지 붙어 느리게 뜨고 로그가 섞이므로 비우고 돌린다.
#
IFS= read -r -d '' PROFILE_SH <<'SH' || true
set -euo pipefail
run="$1"; secs="$2"; mode="$3"; pyro="$4"; app="$5"; trim="$6"; ver="$7"
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
    "$pyro/ingest?name=lunchcatch.$kind%7Brun%3D$run%7D&from=$from&until=$until&format=folded&sampleRate=$rate&spyName=javaspy&units=samples&aggregationType=sum"
  echo "profile $kind $from $(wc -l < "$work/folded-$kind.txt") stacks"
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
      out=$(docker exec "$app" "$ap/asprof" -d 10 -e itimer -i 10ms --wall 50ms -o jfr -f /tmp/lt-profile.jfr 1 2>&1) \
        || { echo "$out" >&2; exit 1; }
      until=$(date +%s)
      for kind in cpu wall; do
        docker exec "$app" env -u JDK_JAVA_OPTIONS "$ap/jfrconv" "--$kind" -o collapsed /tmp/lt-profile.jfr "/tmp/lt-profile-$kind.txt" > /dev/null
        docker cp "$app:/tmp/lt-profile-$kind.txt" "$work/raw-$kind.txt" > /dev/null
        send "$kind" "$work/raw-$kind.txt" "$from" "$until"
      done
      ;;
  esac
done
SH

#
# 앱 서버마다 프로파일링을 붙여 그 시간 동안 Pyroscope 로 보낸다. 모드는 cpu, wall, both 다.
# 시험 환경은 앱 서버 모두에 SSM 으로 함께 보내고 모니터링 서버의 Pyroscope(lunch-catch/lc-infra#71)로 보낸다.
#
profile() {
  local name="${1:?회차 이름을 준다}" secs="${2:-60}" mode="${3:-wall}" pyro id trim pids="" pid rc=0
  case "$mode" in cpu|wall|both) ;; *) die "모드는 cpu, wall, both 다" ;; esac
  if [ -n "${LOCAL:-}" ]; then
    trim=$(mktemp)
    printf '%s\n' "$TRIM_PY" > "$trim"
    bash -c "$PROFILE_SH" profile "$name" "$secs" "$mode" "http://localhost:${PYROSCOPE_PORT:-14040}" \
      "$LOCAL_PROJECT-app-1" "$trim" "$ASPROF_VERSION"
    return
  fi
  pyro="http://$(aws ec2 describe-instances --filters "Name=tag:Project,Values=$PROJECT" "Name=tag:Role,Values=monitoring" \
    "Name=instance-state-name,Values=running" --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text):4040"
  for id in $(instances app); do
    # tail 이 프로파일러의 실패를 가리지 않게 pipefail 로 돈다
    remote "$id" $(( secs + 300 )) "set -o pipefail
cat > /tmp/lt-trim.py <<'PY'
$TRIM_PY
PY
cat > /tmp/lt-profile.sh <<'SH'
$PROFILE_SH
SH
bash /tmp/lt-profile.sh $name $secs $mode $pyro $PROJECT /tmp/lt-trim.py $ASPROF_VERSION | tail -3" &
    pids="$pids $!"
  done
  # 앱 서버 하나라도 프로파일을 보내지 못했으면 실패로 끝난다
  for pid in $pids; do
    wait "$pid" || rc=1
  done
  return "$rc"
}

#
# 앱의 compose 덮어쓰기 파일을 낸다. 인자는 트레이싱의 run 라벨, 에이전트 jar 경로, 연결 풀을 끌지(1)다.
# 시험 환경과 로컬 모두 앱 서비스가 app 이고 같은 compose 에 alloy 가 있어 같은 파일을 쓴다.
#
# 라벨을 주면 OpenTelemetry 에이전트를 붙인다(#1099).
# 운영 compose 가 JAVA_TOOL_OPTIONS 에 힙과 DNS 설정을 넣어 에이전트는 JDK_JAVA_OPTIONS 로 준다.
# 남길 트레이스는 Alloy 의 테일 샘플링이 고른다(lunch-catch/lc-infra#72). 지표와 로그는 지금 길을 그대로 쓴다.
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
      OTEL_TRACES_SAMPLER: always_on
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
  local label="${1:-}" nopool="${2:-}" extra="${3:-}" app_extra="${4:-}" batch_extra="${5:-}" dir="$ROOT/build/otel" over="" i svc
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
  # 부하 시험 환경처럼 배치를 따로 띄운다(#1244). 앱은 스케줄러를 끄고 배치가 batch 프로필로 작업을 돈다
  LC_BACKEND_DIR="$ROOT" docker compose -f "$LC_INFRA_DIR/observability/local/compose.yaml" \
    -f "$LC_INFRA_DIR/observability/local/compose.lt-like.yaml" ${over:+-f "$over"} \
    up -d --no-build app batch
  for svc in app batch; do
    for i in $(seq 1 60); do
      docker exec "$LOCAL_PROJECT-$svc-1" curl -fs localhost:8081/actuator/health/readiness > /dev/null 2>&1 && break
      sleep 5
    done
    echo "$svc $(docker exec "$LOCAL_PROJECT-$svc-1" curl -fs localhost:8081/actuator/health/readiness)"
    docker exec "$LOCAL_PROJECT-$svc-1" sh -c 'echo "JDK_JAVA_OPTIONS=${JDK_JAVA_OPTIONS:-}"'
    docker exec "$LOCAL_PROJECT-$svc-1" sh -c 'if [ -n "${SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED:-}" ]; then echo "SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED=$SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED"; else echo "pool on"; fi'
  done
}

