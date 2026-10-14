-- 최근 60초 요청 시각을 정렬 집합에 두고 센다. 정리, 세기, 기록을 한 번에 해야 두 요청이 같은 빈자리를 함께 차지하지 않는다.
-- KEYS[1] 사용자 키, ARGV[1] 지금(ms), ARGV[2] 창 길이(ms), ARGV[3] 한도, ARGV[4] 이번 요청의 항목 이름
-- 돌려주는 값은 {받았으면 1 아니면 0, 기록 뒤 건수, 가장 오래된 요청 시각(ms)}
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
local count = redis.call('ZCARD', KEYS[1])

local accepted = 0
if count < limit then
    redis.call('ZADD', KEYS[1], now, ARGV[4])
    redis.call('PEXPIRE', KEYS[1], window)
    count = count + 1
    accepted = 1
end

local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
return {accepted, count, tonumber(oldest[2])}
