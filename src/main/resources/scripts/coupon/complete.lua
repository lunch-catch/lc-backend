-- 커밋된 예약을 완료로 반영한다. KEYS 는 available, pending, meta 순서이고 ARGV 는 순번과 예약 ID다.
-- 큐가 READY가 아니거나 같은 예약이 pending 에 없으면 바꾸지 않고 0을 돌려준다.
-- completed 를 1 늘린 결과가 total 과 같으면 SOLD_OUT으로 바꾸고 1을 돌려준다.
if redis.call('HGET', KEYS[3], 'state') ~= 'READY' then return 0 end
local expires = tonumber(redis.call('HGET', KEYS[3], 'expiresAt'))
local time = redis.call('TIME')
if not expires or expires <= time[1] * 1000 + math.floor(time[2] / 1000) then return 0 end
for i = 1, #KEYS do redis.call('PEXPIREAT', KEYS[i], expires) end
if redis.call('ZREM', KEYS[2], ARGV[1] .. ':' .. ARGV[2]) == 0 then return 0 end
local completed = redis.call('HINCRBY', KEYS[3], 'completed', 1)
if completed == tonumber(redis.call('HGET', KEYS[3], 'total')) then
    redis.call('HSET', KEYS[3], 'state', 'SOLD_OUT')
    return 1
end
return 0
