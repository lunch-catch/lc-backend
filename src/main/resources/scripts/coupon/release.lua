-- 롤백된 예약의 순번을 큐에 돌려놓는다. KEYS 는 available, pending, meta 순서이고 ARGV 는 순번과 예약 ID다.
-- 큐가 READY가 아니거나 같은 예약이 pending 에 없으면 늦게 온 반납이므로 바꾸지 않고 0을 돌려준다.
-- 예약을 지운 뒤 순번을 available 끝에 넣고 1을 돌려준다.
if redis.call('HGET', KEYS[3], 'state') ~= 'READY' then return 0 end
local expires = tonumber(redis.call('HGET', KEYS[3], 'expiresAt'))
local time = redis.call('TIME')
if not expires or expires <= time[1] * 1000 + math.floor(time[2] / 1000) then return 0 end
if redis.call('ZREM', KEYS[2], ARGV[1] .. ':' .. ARGV[2]) == 0 then return 0 end
redis.call('RPUSH', KEYS[1], ARGV[1])
for i = 1, #KEYS do redis.call('PEXPIREAT', KEYS[i], expires) end
return 1
