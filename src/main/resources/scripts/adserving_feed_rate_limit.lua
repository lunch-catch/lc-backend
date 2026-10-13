-- 사용자 한 명의 최근 창 안 피드 요청 시각을 정렬 집합에 두고 센다.
-- 창 밖으로 밀려난 시각을 지우고, 한도에 닿았으면 넣지 않고 거절한다. 거절한 요청은 세지 않는다.
-- 돌려주는 값: { 허용 1 또는 거절 0, 창 안 가장 오래된 요청 시각(밀리초) 또는 한도 전이면 -1 }
-- KEYS[1] 집합 키, ARGV[1] 지금(밀리초), ARGV[2] 창 길이(밀리초), ARGV[3] 한도, ARGV[4] 이번 요청의 고유 값
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
local count = redis.call('ZCARD', KEYS[1])

local function oldest()
    return tonumber(redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')[2])
end

if count >= limit then
    return { 0, oldest() }
end

redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)

if count + 1 >= limit then
    return { 1, oldest() }
end
return { 1, -1 }
