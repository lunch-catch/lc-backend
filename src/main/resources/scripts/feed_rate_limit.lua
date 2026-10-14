-- 최근 창 안의 요청 시각을 정렬 집합에 두고 센다. 분 단위로 끊어 세지 않는다.
-- 세기와 넣기를 한 스크립트로 묶어야 동시에 온 두 요청이 함께 여섯째로 통과하지 않는다.
-- 거부된 요청은 넣지 않는다. 넣으면 알려 준 시각에 다시 요청해도 또 거부될 수 있다.
-- KEYS[1] 사용자 키, ARGV[1] 지금(ms), ARGV[2] 창 길이(ms), ARGV[3] 상한, ARGV[4] 이번 요청의 고유 값
-- 반환: {통과면 1 아니면 0, 창 안의 요청 수, 창 안에서 가장 오래된 요청 시각(ms)}
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
local count = redis.call('ZCARD', KEYS[1])

local allowed = 0
if count < limit then
    redis.call('ZADD', KEYS[1], now, ARGV[4])
    redis.call('PEXPIRE', KEYS[1], window)
    count = count + 1
    allowed = 1
end

local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
return {allowed, count, tonumber(oldest[2])}
