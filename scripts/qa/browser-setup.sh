#!/usr/bin/env bash
#
# QA 에이전트가 화면을 누를 Playwright 를 .qa/tools 에 받는다 (#889).
# 저장소의 의존성에 넣지 않는다. 백엔드 빌드에 노드 의존성이 섞이지 않게 git 이 무시하는 .qa 아래에 둔다.
#
#   scripts/qa/browser-setup.sh
#
# 화면 스크립트는 NODE_PATH=.qa/tools/node_modules 로 돌린다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIR="$ROOT/.qa/tools"
VERSION="1.48.2"

mkdir -p "$DIR"
cd "$DIR"
[ -f package.json ] || echo '{"private":true}' > package.json
npm install --no-audit --no-fund "playwright@$VERSION" > /dev/null
npx playwright install chromium > /dev/null
echo "준비했다. NODE_PATH=$DIR/node_modules"
