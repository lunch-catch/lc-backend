-- 진행 중에는 PENDING으로 표시하고, DB 커밋과 게시 시도가 끝나면 REVOKED로 확정한다.
-- 진행 중인 중복 요청은 계정 토큰을 폐기하지 않는다.
-- 같은 RT의 동시 재발급 방지.
local value = redis.call('GET', KEYS[1])
if not value then return nil end
local role = string.match(value, '^%d+|([^|]+)|')
if role ~= ARGV[1] then return value end
if string.sub(value, -8) == '|REVOKED' or string.sub(value, -8) == '|PENDING' then return value end
redis.call('SET', KEYS[1], value .. '|PENDING', 'KEEPTTL')
return value
