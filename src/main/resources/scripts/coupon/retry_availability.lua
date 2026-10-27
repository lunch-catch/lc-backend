local BLOCKED, SOLD_OUT, AVAILABLE, PENDING = -1, 0, 1, 2
-- 예약 대기 중인 요청이 소진 확정과 재고 반납을 한 번의 호출로 구분한다.
local state = redis.call('HGET', KEYS[3], 'state')
if state ~= 'READY' and state ~= 'SOLD_OUT' then return BLOCKED end
local total = tonumber(redis.call('HGET', KEYS[3], 'total'))
local completed = tonumber(redis.call('HGET', KEYS[3], 'completed'))
local expires = tonumber(redis.call('HGET', KEYS[3], 'expiresAt'))
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
if not total or not completed or not expires or expires <= now then return BLOCKED end
local oldest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
if #oldest > 0 and now - tonumber(oldest[2]) >= tonumber(ARGV[1]) then
    redis.call('HSET', KEYS[3], 'state', 'BLOCKED')
    return BLOCKED
end
local available = redis.call('LLEN', KEYS[1])
local pending = redis.call('ZCARD', KEYS[2])
if completed < 0 or completed > total or available + pending + completed ~= total then return BLOCKED end
if state == 'SOLD_OUT' and completed ~= total then return BLOCKED end
if completed == total then return SOLD_OUT end
if available > 0 then return AVAILABLE end
return PENDING
