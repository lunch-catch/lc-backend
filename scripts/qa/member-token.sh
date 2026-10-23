#!/usr/bin/env bash
#
# 로컬 앱이 받는 회원 Access Token 을 만든다 (#889).
# 회원 로그인은 카카오뿐이라 QA 에이전트가 회원 시나리오를 돌릴 길이 없어 로컬의 jwt.secret 으로 직접 서명한다.
#
#   scripts/qa/member-token.sh <memberId>
#
# 쿠키 accessToken 에 넣어 부른다. 수명은 앱과 같은 30분이다.
# 비밀값은 JWT_SECRET 이 있으면 그 값을, 없으면 application-local.yml 의 jwt.secret 을 쓴다.
# 개발 서버와 운영 서버는 비밀값이 달라 이 토큰을 받지 않는다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ID="${1:-}"
[ -n "$ID" ] || { printf 'usage: %s <memberId>\n' "$0" >&2; exit 1; }

SECRET="${JWT_SECRET:-}"
if [ -z "$SECRET" ] && [ -f "$ROOT/application-local.yml" ]; then
  SECRET=$(awk '/^jwt:/{j=1;next} /^[^ #]/{j=0} j && /^  secret:/{sub(/^  secret:[ ]*/,""); gsub(/"/,""); print; exit}' "$ROOT/application-local.yml")
fi
[ -n "$SECRET" ] || { echo "jwt.secret 을 찾지 못했다. JWT_SECRET 으로 준다" >&2; exit 1; }

# jjwt 의 signWith 가 키 길이로 알고리즘을 고르므로 같은 기준으로 고른다
LEN=$(printf '%s' "$SECRET" | wc -c | tr -d ' ')
if [ "$LEN" -ge 64 ]; then ALG=HS512; DGST=sha512
elif [ "$LEN" -ge 48 ]; then ALG=HS384; DGST=sha384
else ALG=HS256; DGST=sha256
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
NOW=$(date +%s)
HEADER=$(printf '{"alg":"%s"}' "$ALG" | b64url)
PAYLOAD=$(printf '{"sub":"%s","role":"MEMBER","iat":%d,"exp":%d}' "$ID" "$NOW" $((NOW + 1800)) | b64url)
SIG=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -"$DGST" -hmac "$SECRET" -binary | b64url)
printf '%s.%s.%s\n' "$HEADER" "$PAYLOAD" "$SIG"
