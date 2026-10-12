-- KEYS: 활성 포인터, 발급 순번 키. ARGV: DB 폐기 순번, 기본 토큰 키 접두사.
local current = redis.call('GET', KEYS[2])
if current and current > ARGV[1] then
    return 0
end
local hash = redis.call('GET', KEYS[1])
if hash then
    local key = ARGV[2] .. hash
    local value = redis.call('GET', key)
    local ttl = redis.call('PTTL', key)
    if value and ttl > 0 and string.sub(value, -8) ~= '|REVOKED' then
        redis.call('SET', key, value .. '|REVOKED', 'PX', ttl)
    end
end
redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[2], ARGV[1])
return 1
