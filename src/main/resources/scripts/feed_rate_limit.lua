-- 피드 요청 제한. 최근 windowMs 안의 요청 시각을 정렬 집합에 두고 센다(슬라이딩 로그).
-- 세기, 넣기, 만료를 한 번에 처리한다. 나눠 보내면 두 요청이 같은 수를 읽고 둘 다 들어온다.
-- ARGV: 1=지금(ms), 2=windowMs, 3=허용 횟수, 4=이 요청의 고유 값
-- 반환: {들어왔으면 1 아니면 0, 다음 요청이 들어올 수 있는 시각(ms). 지금 바로면 -1}
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- 정확히 windowMs 전의 요청은 창 밖이다. 그래서 다음 시각을 가장 오래된 요청 + windowMs 로 알려 주면 그때 들어온다
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)

local count = redis.call('ZCARD', KEYS[1])
if count >= limit then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return {0, tonumber(oldest[2]) + window}
end

redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)

if count + 1 >= limit then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return {1, tonumber(oldest[2]) + window}
end
return {1, -1}
