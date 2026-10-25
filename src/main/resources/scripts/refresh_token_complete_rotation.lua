-- KEYS[1] = 옛 Refresh Token 기본 키
-- ARGV[1] = 이번 요청이 발급한 새 토큰 해시
local value = redis.call('GET', KEYS[1])
local suffix = '|ROTATING:' .. ARGV[1]
if not value or string.sub(value, -#suffix) ~= suffix then
    return 0
end

local original = string.sub(value, 1, -#suffix - 1)
redis.call('SET', KEYS[1], original .. '|REVOKED', 'KEEPTTL')
return 1
