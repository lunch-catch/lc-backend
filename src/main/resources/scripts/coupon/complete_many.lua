-- DB에서 커밋된 한 이벤트의 예약들을 함께 완료하며 이미 지운 예약은 다시 세지 않는다.
if redis.call('HGET', KEYS[3], 'state') ~= 'READY' then return 0 end
local expires = tonumber(redis.call('HGET', KEYS[3], 'expiresAt'))
local time = redis.call('TIME')
if not expires or expires <= time[1] * 1000 + math.floor(time[2] / 1000) then return 0 end
for i = 1, #KEYS do redis.call('PEXPIREAT', KEYS[i], expires) end
local removed = 0
for i = 1, #ARGV, 2 do
    removed = removed + redis.call('ZREM', KEYS[2], ARGV[i] .. ':' .. ARGV[i + 1])
end
if removed == 0 then return 0 end
local completed = redis.call('HINCRBY', KEYS[3], 'completed', removed)
if completed == tonumber(redis.call('HGET', KEYS[3], 'total')) then
    redis.call('HSET', KEYS[3], 'state', 'SOLD_OUT')
    return 1
end
return 0
