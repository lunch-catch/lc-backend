-- 처리 중 표시가 남아 있을 때만 재사용 감지용 표시로 확정하며, 남은 만료 시간은 유지한다.
local value = redis.call('GET', KEYS[1])
if value and string.sub(value, -8) == '|PENDING' then
    redis.call('SET', KEYS[1], string.sub(value, 1, -9) .. '|REVOKED', 'KEEPTTL')
    return 1
end
return 0
