-- 회원의 최근 60초 피드 요청을 세고, 한도 안이면 이 요청의 자리를 잡는다.
-- 세기와 자리 잡기를 한 번에 해야 같은 회원의 요청이 동시에 와도 한도를 넘지 않는다.
-- KEYS[1] 회원의 최근 요청 시각 집합
-- ARGV[1] 지금(밀리초), ARGV[2] 창 길이(밀리초), ARGV[3] 한도, ARGV[4] 이 요청의 식별자
-- 돌려주는 값 {잡았으면 1 아니면 0, 다음 요청이 거부되지 않는 가장 이른 시각(밀리초)}
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
local count = redis.call('ZCARD', KEYS[1])
local acquired = 0
if count < limit then
    redis.call('ZADD', KEYS[1], now, ARGV[4])
    redis.call('PEXPIRE', KEYS[1], window)
    count = count + 1
    acquired = 1
end

if count < limit then
    return {acquired, now}
end
local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
return {acquired, tonumber(oldest[2]) + window}
