-- 큐 상태와 오래된 예약, 수량 불일치를 한 번 검사한 뒤 ARGV[2] 부터의 예약 ID마다 순번을 하나씩 꺼낸다.
-- KEYS 는 available 목록, pending 정렬 집합, meta 해시 순서이고 ARGV[1] 은 오래된 예약으로 보는 밀리초다.
-- 결과는 예약 ID와 같은 순서의 배열이며 양수는 순번, 0은 소진, -1은 차단, -2는 남은 순번 없음이다.
-- 중간에 순번이 떨어지면 남은 칸을 모두 같은 부호로 채운다.
local n = #ARGV - 1
local out = {}
-- 아직 채우지 않은 결과 칸을 모두 code로 채워 돌려준다.
local function fill(code)
    for i = #out + 1, n do out[i] = code end
    return out
end
if redis.call('HGET', KEYS[3], 'state') ~= 'READY' then return fill(-1) end
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local expires = tonumber(redis.call('HGET', KEYS[3], 'expiresAt'))
if not expires or expires <= now then return fill(-1) end
for i = 1, #KEYS do redis.call('PEXPIREAT', KEYS[i], expires) end
local oldest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
if #oldest > 0 and now - tonumber(oldest[2]) >= tonumber(ARGV[1]) then
    redis.call('HSET', KEYS[3], 'state', 'BLOCKED')
    return fill(-1)
end
local total = tonumber(redis.call('HGET', KEYS[3], 'total'))
local completed = tonumber(redis.call('HGET', KEYS[3], 'completed'))
if not total or not completed or completed < 0 or completed > total
    or redis.call('LLEN', KEYS[1]) + redis.call('ZCARD', KEYS[2]) + completed ~= total then
    redis.call('HSET', KEYS[3], 'state', 'BLOCKED')
    return fill(-1)
end
if completed == total then return fill(0) end
-- 예약 ID마다 순번을 하나 꺼내 pending 에 현재 시각과 함께 넣어 커밋이나 반납을 기다린다.
for i = 1, n do
    local sequence = redis.call('LPOP', KEYS[1])
    if not sequence then return fill(-2) end
    redis.call('ZADD', KEYS[2], now, sequence .. ':' .. ARGV[i + 1])
    redis.call('PEXPIREAT', KEYS[2], expires)
    out[i] = tonumber(sequence)
end
return out
