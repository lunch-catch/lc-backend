-- KEYS: 당일 소진 해시 billing:day:{businessDate}:{campaignId}
-- ARGV: 새 예약액
-- 해시가 없으면 만들지 않는다. 다음 차감이 budget_daily_plan 정본으로 다시 만든다.
-- 지금 값보다 크지 않으면 쓰지 않는다. 늘어난 몫만 반영하고(D-4), 뒤섞여 들어온 낡은 값이 덮지 않게 한다.
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
local current = redis.call('HGET', KEYS[1], 'reserved')
if current and tonumber(current) >= tonumber(ARGV[1]) then
    return 0
end
redis.call('HSET', KEYS[1], 'reserved', ARGV[1])
return 1
