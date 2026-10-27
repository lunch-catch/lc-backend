#!/usr/bin/env bash
#
# 로컬 compose 의 MySQL 과 Valkey 를 떠 두고 되돌린다 (#889).
# QA 에이전트가 시나리오 묶음 하나를 돌린 뒤 기본 세계가 들어간 직후 상태로 돌아가는 데 쓴다.
#
#   scripts/qa/snapshot.sh save       지금 상태를 .qa/snapshot 에 뜬다
#   scripts/qa/snapshot.sh restore    뜬 상태로 되돌린다
#
# 되돌리기 전에 앱을 멈춘다. 앱이 연결과 메모리 캐시를 들고 있으면 되돌린 값과 어긋난다.
# 기본 세계는 기동한 날을 기준일로 들어간다. 날이 바뀌면 되돌려도 어제 데이터라 새로 띄워 다시 뜬다.
# 로컬 컨테이너만 만진다. 개발 서버와 운영 서버에는 닿지 않는다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIR="$ROOT/.qa/snapshot"

# compose 와 같은 값을 쓴다. .env 가 있으면 그 값이 이긴다
if [ -f "$ROOT/.env" ]; then
  set -a; . "$ROOT/.env"; set +a
fi
MYSQL="${MYSQL_CONTAINER_NAME:-lunchcatch-mysql}"
VALKEY="${VALKEY_CONTAINER_NAME:-lunchcatch-valkey}"
DB="${MYSQL_DATABASE:-lunchcatch}"
PW="${MYSQL_ROOT_PASSWORD:-root}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "오류: $*"; exit 1; }

running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }

save() {
  running "$MYSQL" || die "$MYSQL 이 떠 있지 않다. ./gradlew bootRun 으로 먼저 띄운다"
  running "$VALKEY" || die "$VALKEY 이 떠 있지 않다"
  mkdir -p "$DIR"
  docker exec -e MYSQL_PWD="$PW" "$MYSQL" mysqldump -uroot --default-character-set=utf8mb4 --single-transaction --routines --triggers \
    --events --add-drop-database --databases "$DB" > "$DIR/mysql.sql"
  docker exec "$VALKEY" valkey-cli SAVE > /dev/null
  docker cp "$VALKEY:/data/dump.rdb" "$DIR/valkey.rdb"
  date +%F > "$DIR/date"
  log "떴다. $DIR ($(du -sh "$DIR" | cut -f1))"
}

restore() {
  [ -f "$DIR/mysql.sql" ] && [ -f "$DIR/valkey.rdb" ] || die "뜬 상태가 없다. save 를 먼저 돌린다"
  if [ "$(cat "$DIR/date")" != "$(date +%F)" ]; then
    die "$(cat "$DIR/date") 에 뜬 상태다. 기본 세계의 기준일이 지나 docker compose down -v 뒤 새로 띄우고 save 한다"
  fi
  if curl -fsS --max-time 2 http://localhost:8081/actuator/health > /dev/null 2>&1; then
    die "앱이 떠 있다. 멈추고 되돌린다"
  fi
  running "$MYSQL" || die "$MYSQL 이 떠 있지 않다. docker start $MYSQL"
  docker exec -i -e MYSQL_PWD="$PW" "$MYSQL" mysql -uroot --default-character-set=utf8mb4 < "$DIR/mysql.sql"
  # 멈출 때 Valkey 가 제 상태를 dump.rdb 로 쓰므로 멈춘 뒤에 덮어써야 뜬 상태가 읽힌다
  docker stop "$VALKEY" > /dev/null
  docker cp "$DIR/valkey.rdb" "$VALKEY:/data/dump.rdb"
  docker start "$VALKEY" > /dev/null
  for _ in $(seq 1 20); do
    docker exec "$VALKEY" valkey-cli ping > /dev/null 2>&1 && { log "되돌렸다"; return; }
    sleep 1
  done
  die "Valkey 가 다시 뜨지 않았다"
}

case "${1:-}" in
  save) save ;;
  restore) restore ;;
  *) printf 'usage: %s save|restore\n' "$0" >&2; exit 1 ;;
esac
