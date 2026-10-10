-- 더 오래된 차단 시각으로 덮어쓰거나 기존 차단 정보의 보관 시간을 줄이지 않는다.
-- AT 차단 시각이 과거로 되돌아가는 문제를 방지한다.
local current = redis.call('GET', KEYS[1])
if current and current > ARGV[1] then return 0 end
local ttl = tonumber(ARGV[2])
local remaining = redis.call('PTTL', KEYS[1])
if remaining == -1 then
    redis.call('SET', KEYS[1], ARGV[1])
else
    redis.call('SET', KEYS[1], ARGV[1], 'PX', math.max(ttl, remaining))
end
return 1
