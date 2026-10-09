-- 기존 RT를 다시 사용할 수 없게 표시하며, 현재 RT를 가리키는 정보는 아직 변경하지 않는다.
-- 새 토큰은 DB 커밋 후 saveIfNewer로 게시한다.
-- 처리 순서는 '기존 RT 사용 차단 → DB에 새 RT 해시 저장·커밋 → 발급 순번을 비교해 Redis에 새 RT 저장' 순이다.
local value = redis.call('GET', KEYS[1])
if not value then return nil end
local role = string.match(value, '^%d+|([^|]+)|')
if role ~= ARGV[1] then return value end
if string.sub(value, -8) == '|REVOKED' then return value end
redis.call('SET', KEYS[1], value .. '|REVOKED', 'KEEPTTL')
return value
