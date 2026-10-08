-- KEYS[1] = 옛 Refresh Token 기본 키
-- KEYS[2] = 새 Refresh Token 기본 키
-- KEYS[3] = 계정의 활성 Refresh Token 포인터
-- ARGV[1] = 옛 토큰 해시
-- ARGV[2] = 새 토큰 해시
--
-- DB 회전 실패가 확인됐을 때만 호출한다. 이 요청이 만든 Redis 상태가 그대로 남아 있을 때만
-- 새 토큰을 지우고 tombstone을 정상 레코드로 복구한다. 다른 로그인이나 회전이 끼어들었다면
-- 아무것도 바꾸지 않는다.
local oldValue = redis.call('GET', KEYS[1])
local newValue = redis.call('GET', KEYS[2])
local activeHash = redis.call('GET', KEYS[3])

if not oldValue or not newValue or activeHash ~= ARGV[2] then
    return 0
end

if string.sub(oldValue, -8) ~= '|REVOKED' then
    return 0
end

local restoredValue = string.sub(oldValue, 1, -9)
if restoredValue ~= newValue then
    return 0
end

local remainingTtl = redis.call('PTTL', KEYS[1])
if remainingTtl <= 0 then
    return 0
end

redis.call('DEL', KEYS[2])
redis.call('SET', KEYS[1], restoredValue, 'PX', remainingTtl)
redis.call('SET', KEYS[3], ARGV[1], 'PX', remainingTtl)
return 1
