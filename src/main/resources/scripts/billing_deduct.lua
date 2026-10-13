-- KEYS[1]: 당일 소진 해시 billing:day:{businessDate}:{campaignId}
-- KEYS[2]: 처리 표시 billing:served:{serveId}
-- ARGV[1]: 단가, ARGV[2]: 처리 표시 TTL(초)
-- 반환: BILLED, NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, HASH_MISSING 중 하나
--
-- 같은 serveId 의 재호출은 처음 판정을 그대로 돌려주고 소진을 늘리지 않는다.
-- 해시가 없으면 아무것도 쓰지 않는다. 처리 표시도 남기지 않아야 재구성 뒤 다시 판정할 수 있다.
-- 중단이면 예산을 보지 않는다. 예약액 이하일 때만 소진을 늘리고 BILLED 로 판정한다.
local served = redis.call('GET', KEYS[2])
if served then
    return served
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'HASH_MISSING'
end
local state = redis.call('HMGET', KEYS[1], 'reserved', 'spend', 'paused')
local result
if state[3] == '1' then
    result = 'NOT_BILLED_PAUSED'
else
    local reserved = tonumber(state[1]) or 0
    local spend = tonumber(state[2]) or 0
    local unitPrice = tonumber(ARGV[1])
    if spend + unitPrice <= reserved then
        redis.call('HINCRBY', KEYS[1], 'spend', unitPrice)
        result = 'BILLED'
    else
        result = 'NOT_BILLED_BUDGET'
    end
end
redis.call('SET', KEYS[2], result, 'EX', tonumber(ARGV[2]))
return result
