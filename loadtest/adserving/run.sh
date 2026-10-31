#!/usr/bin/env bash
#
# lc-infra 의 member-env.sh 로 띄운 부하 시험 환경에서 광고 서빙 시험을 돌린다(#997).
# 인스턴스에 SSM Run Command 로 스크립트를 보내고 끝날 때까지 기다려 출력을 보인다.
#
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh seed [회원 수]          시험 데이터를 RDS 에 넣는다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh prepare                 그날 00:00 묶음을 다시 돌린다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh run <이름> <회원 시작 번호> [초당 피드] [유지 시간]
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh metrics <시작 ms> <끝 ms>  그 구간의 서버 지표
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh rows                    시험 데이터의 행 수
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh switch <sha>            앱과 배치를 그 커밋 이미지로 다시 띄운다. GHCR 에 있어야 한다
#   AWS_PROFILE=<프로필> loadtest/adserving/run.sh reset [회원 수]         Valkey 를 비우고 시험 데이터를 다시 넣는다
#
# 시나리오와 시드 생성기는 떠 있는 앱과 같은 커밋(SSM current-sha)에서 받는다. 값이 없으면 develop 이다.
# LOADTEST_REF 로 브랜치나 커밋을 따로 고를 수 있다.
set -euo pipefail

# lc-infra 의 member-env.sh 가 어느 계정에서나 이 접두사로 띄운다 (lunch-catch/lc-infra#53). 리소스, SSM 경로, 컨테이너 이름이 모두 이것이다
PROJECT="${PROJECT:-lunchcatch-lt}"
REGION="${AWS_REGION:-ap-northeast-2}"
REPO="https://github.com/lunch-catch/lc-backend.git"
IMAGE_REPO="lunch-catch/lc-backend"
export AWS_REGION="$REGION"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
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

# 부하 생성기에서 시나리오를 받고 서명 키를 읽는다
K6_ENV="
set -a; . /opt/loadtest/env; set +a
DIR=/opt/loadtest/lc-backend-run
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=\"\$DIR\"
[ -d \"\$DIR/.git\" ] || git clone -q --depth 1 --no-checkout $REPO \"\$DIR\"
git -C \"\$DIR\" fetch -q --depth 1 origin $REF && git -C \"\$DIR\" reset -q --hard FETCH_HEAD
echo \"scenario \$(git -C \"\$DIR\" rev-parse --short HEAD)\"
cd \"\$DIR\"
JWT=\$(aws ssm get-parameter --name /$PROJECT/jwt-signing-key --with-decryption --region $REGION --query Parameter.Value --output text)
"

#
# 시드는 앱 서버에서 돈다. DB 보안 그룹이 부하 생성기를 받지 않아서다(부하 시험 문서 3장).
# 앱 서버에는 저장소 사본이 없어 생성기만 raw 로 받는다. 경로가 $REF 에 박히므로
# 이 파일을 옮기면 그 커밋부터만 받힌다. 경로를 옮긴 커밋보다 앞선 ref 를 쓸 때는
# LOADTEST_REF 를 이 커밋 이후로 준다 (#1080).
#
seed() {
  remote "$(instance app)" 900 "set -euo pipefail
$DB_ENV
rm -rf /tmp/lt && mkdir -p /tmp/lt && cd /tmp/lt
curl -fsSL https://raw.githubusercontent.com/lunch-catch/lc-backend/$REF/loadtest/adserving/gen_data.py -o gen.py
docker run --rm -v /tmp/lt:/w python:3.12-slim python /w/gen.py --date \"\$D\" --members $members --stores 1000 > seed.sql
mysql < seed.sql
mysql -N -e \"select 'member', count(*) from member where member_id >= 30000001;
select 'batch', status from batch_execution_log where business_date = '\$D' and job_name = 'DAILY_0000';\""
}

case "${1:-}" in
  seed)
    members="${2:-40000}"
    seed
    ;;
  switch)
    sha="${2:?이미지 커밋을 준다}"
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
    for id in $(instances app) $(instances batch); do
      echo "restart $id"
      remote "$id" 600 "set -euo pipefail
/usr/local/bin/$PROJECT-refresh-env
systemctl restart $PROJECT
for i in \$(seq 1 60); do
  curl -fs localhost:8081/actuator/health/readiness > /dev/null && break; sleep 5
done
docker inspect $PROJECT --format '{{.Config.Image}}'
curl -fs localhost:8081/actuator/health/readiness" &
    done
    wait
    aws autoscaling resume-processes --auto-scaling-group-name "$PROJECT-app" --scaling-processes ReplaceUnhealthy
    ;;
  reset)
    # 버전 사이에 앞 버전이 쌓은 것을 지운다(부하 시험 문서 5장). 시드가 시험 데이터와 그날 배치 기록을 지우고 다시 넣는다
    members="${2:-40000}"
    remote "$(instance app)" 300 "set -euo pipefail
CACHE=\$(docker inspect $PROJECT --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^VALKEY_HOST=' | cut -d= -f2-)
docker run --rm valkey/valkey:9.0-alpine valkey-cli -h \"\$CACHE\" FLUSHALL
docker run --rm valkey/valkey:9.0-alpine valkey-cli -h \"\$CACHE\" DBSIZE"
    seed
    ;;
  prepare)
    remote "$(instance load-test)" 1200 "set -euo pipefail
$K6_ENV
k6 run -q -e BASE_URL=\"\$BASE_URL\" -e JWT_SECRET=\"\$JWT\" -e BUSINESS_DATE=\$(TZ=Asia/Seoul date +%F) -e WAIT_SECONDS=900 \
  loadtest/adserving/prepare.js 2>&1 | grep -E 'level=' || true"
    ;;
  run)
    name="${2:?회차 이름을 준다}"; base="${3:?회원 시작 번호를 준다}"; rate="${4:-100}"; hold="${5:-5m}"
    remote "$(instance load-test)" 1800 "set -euo pipefail
$K6_ENV
ulimit -n 250000
start=\$(date +%s%3N)
k6 run -q -o experimental-prometheus-rw --tag testid=adserving-$name \
  -e BASE_URL=\"\$BASE_URL\" -e JWT_SECRET=\"\$JWT\" -e FEED_RATE=$rate -e RAMP_UP_SECONDS=60 -e DURATION=$hold \
  -e MEMBERS=10000 -e MEMBER_BASE=$base -e PRE_VUS=200 -e MAX_VUS=2000 \
  --summary-export /opt/loadtest/summary-$name.json loadtest/adserving/feed_impression.js > /opt/loadtest/k6-$name.log 2>&1 || true
echo \"window \$start \$(date +%s%3N)\"
grep -E '✓|✗|↳|impression_results|http_reqs|http_req_duration|http_req_failed|iterations|vus_max' /opt/loadtest/k6-$name.log | head -40"
    ;;
  metrics)
    start="${2:?시작 ms 를 준다}"; end="${3:?끝 ms 를 준다}"
    remote "$(instance monitoring)" 300 "python3 - $start $end <<'PY'
import json, sys, urllib.parse, urllib.request
s, e = int(sys.argv[1]) / 1000, int(sys.argv[2]) / 1000
w = f'{int(e - s)}s'
def q(expr):
    u = 'http://localhost:9090/api/v1/query?' + urllib.parse.urlencode({'query': expr, 'time': e})
    r = json.load(urllib.request.urlopen(u))['data']['result']
    return [({k: v for k, v in x['metric'].items() if k in ('uri', 'status')}, round(float(x['value'][1]), 3)) for x in r]
Q = {
    'hikari_pending_max': f'max_over_time(sum(hikaricp_connections_pending{{job=~\".*app.*\"}})[{w}:5s])',
    'hikari_active_max': f'max_over_time(sum(hikaricp_connections_active{{job=~\".*app.*\"}})[{w}:5s])',
    'hikari_timeout_inc': f'sum(increase(hikaricp_connections_timeout_total[{w}]))',
    'hikari_acquire_avg_ms': f'1000*sum(increase(hikaricp_connections_acquire_seconds_sum[{w}]))/sum(increase(hikaricp_connections_acquire_seconds_count[{w}]))',
    'hikari_usage_avg_ms': f'1000*sum(increase(hikaricp_connections_usage_seconds_sum[{w}]))/sum(increase(hikaricp_connections_usage_seconds_count[{w}]))',
    'mysql_commit_per_s': f'sum(rate(mysql_global_status_commands_total{{command=\"commit\"}}[{w}]))',
    'mysql_insert_per_s': f'sum(rate(mysql_global_status_commands_total{{command=\"insert\"}}[{w}]))',
    'mysql_threads_running_max': f'max_over_time(max(mysql_global_status_threads_running)[{w}:5s])',
    'app_cpu_avg': f'avg(avg_over_time(process_cpu_usage{{job=~\".*app.*\"}}[{w}]))',
    'app_cpu_max': f'max(max_over_time(process_cpu_usage{{job=~\".*app.*\"}}[{w}]))',
    'loadgen_cpu_max_pct': f'max_over_time((100*(1-avg(rate(node_cpu_seconds_total{{role=\"load-test\",mode=\"idle\"}}[1m]))))[{w}:15s])',
    'served_cards_inc': f'sum(increase(adserving_served_cards_total[{w}]))',
    'serve_log_failed_inc': f'sum(increase(adserving_serve_log_failed_total[{w}]))',
    'serve_log_dropped_inc': f'sum(increase(adserving_serve_log_dropped_total[{w}]))',
    # 노출 수집 v2 부터의 릴레이와 v3 부터의 저장 큐다. 그 전 버전에는 지표가 없어 비어 나온다
    'impression_dispatch_backlog_max': f'max_over_time(sum(adserving_impression_dispatch_backlog)[{w}:5s])',
    'impression_queue_max': f'max_over_time(sum(adserving_impression_queue_size)[{w}:5s])',
    'impression_dropped_inc': f'sum(increase(adserving_impression_dropped_total[{w}]))',
    'impression_write_failed_inc': f'sum(increase(adserving_impression_write_failed_total[{w}]))',
    'impression_write_lost_inc': f'sum(increase(adserving_impression_write_lost_total[{w}]))',
    # 릴레이가 정산 한 번에 걸린 시간의 평균과 초당 넘긴 건수다. 릴레이 처리량의 상한을 가른다
    'relay_deduct_avg_s': f'sum(increase(adserving_impression_dispatch_deduct_seconds_sum[{w}]))/sum(increase(adserving_impression_dispatch_deduct_seconds_count[{w}]))',
    'relay_deduct_rows_per_s': f'sum(rate(adserving_impression_dispatch_deduct_batch_sum[{w}]))',
    'http_5xx_inc': f'sum by (uri, status) (increase(http_server_requests_seconds_count{{status=~\"5..\"}}[{w}]))',
}
for k, v in Q.items():
    print(k, q(v))
PY"
    # RDS 는 Prometheus 가 아니라 CloudWatch 에서 읽는다. t4g 라 CPU 크레딧이 바닥나면 기본 성능으로 떨어진다
    for m in CPUUtilization:Maximum CPUCreditBalance:Minimum; do
      printf 'rds_%s ' "${m%%:*}"
      aws cloudwatch get-metric-statistics --namespace AWS/RDS --metric-name "${m%%:*}" \
        --dimensions Name=DBInstanceIdentifier,Value="$PROJECT-db" --period 60 --statistics "${m##*:}" \
        --start-time "$(date -u -r $((start / 1000)) +%FT%TZ)" --end-time "$(date -u -r $((end / 1000)) +%FT%TZ)" \
        --query "sort_by(Datapoints,&Timestamp)[].${m##*:}" --output text
    done
    # ElastiCache 의 t4g 는 Unlimited 가 없어 CPU 크레딧이나 네트워크 버스트가 바닥나면 기준 성능으로 떨어진다
    for node in $(aws elasticache describe-replication-groups --replication-group-id "$PROJECT-cache" \
        --query 'ReplicationGroups[0].MemberClusters' --output text); do
      for m in EngineCPUUtilization:Maximum CPUCreditBalance:Minimum NetworkBandwidthInAllowanceExceeded:Sum NetworkBandwidthOutAllowanceExceeded:Sum; do
        printf 'valkey_%s_%s ' "$node" "${m%%:*}"
        aws cloudwatch get-metric-statistics --namespace AWS/ElastiCache --metric-name "${m%%:*}" \
          --dimensions Name=CacheClusterId,Value="$node" --period 60 --statistics "${m##*:}" \
          --start-time "$(date -u -r $((start / 1000)) +%FT%TZ)" --end-time "$(date -u -r $((end / 1000)) +%FT%TZ)" \
          --query "sort_by(Datapoints,&Timestamp)[].${m##*:}" --output text
      done
    done
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
    sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
