-- 재구축의 각 단계에서 생성한 키에 같은 절대 만료 시각을 적용하며 만료된 키는 다시 만들지 않는다.
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local expires = tonumber(ARGV[2])
if not expires or expires <= now then return 0 end
if ARGV[1] == 'start' then
    redis.call('DEL', unpack(KEYS))
    redis.call('HSET', KEYS[3], 'state', 'BLOCKED', 'total', ARGV[3], 'completed', ARGV[4], 'expiresAt', ARGV[2])
    redis.call('PEXPIREAT', KEYS[3], expires)
    return 1
end
if redis.call('HGET', KEYS[3], 'state') ~= 'BLOCKED'
    or redis.call('HGET', KEYS[3], 'expiresAt') ~= ARGV[2] then return 0 end
if ARGV[1] == 'append' then
    redis.call('RPUSH', KEYS[1], unpack(ARGV, 3))
    redis.call('PEXPIREAT', KEYS[1], expires)
    return 1
end
if ARGV[1] == 'finish' and redis.call('LLEN', KEYS[1]) == tonumber(ARGV[3]) then
    redis.call('HSET', KEYS[3], 'state', 'READY')
    for i = 1, #KEYS do redis.call('PEXPIREAT', KEYS[i], expires) end
    return 1
end
return 0
