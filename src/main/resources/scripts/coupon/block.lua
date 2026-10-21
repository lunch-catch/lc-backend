-- 차단은 보존 기한 안의 기존 meta만 바꾸며 유실되거나 만료된 키를 새로 만들지 않는다.
local expires = tonumber(redis.call('HGET', KEYS[3], 'expiresAt'))
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
if not expires or expires <= now then return 0 end
redis.call('HSET', KEYS[3], 'state', 'BLOCKED')
for i = 1, #KEYS do redis.call('PEXPIREAT', KEYS[i], expires) end
return 1
