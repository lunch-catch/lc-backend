-- KEYS: 당일 소진 해시 billing:day:{businessDate}:{campaignId}
-- ARGV: 중단 여부(1 또는 0), 변경 시각(epoch 마이크로초)
-- 해시가 없으면 만들지 않는다. 일부 필드만 있는 해시가 생기면 차감 쪽이 예약액 없이 판정한다.
-- 저장된 시각보다 늦은 변경만 쓴다. 커밋 뒤 갱신은 순서가 보장되지 않아 DB 와 같은 규칙을 둔다.
-- 마이크로초 값은 2의 53승보다 작아 Lua 숫자로 정확히 비교된다.
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
local current = redis.call('HGET', KEYS[1], 'changedAt')
if current and tonumber(current) >= tonumber(ARGV[2]) then
    return 0
end
redis.call('HSET', KEYS[1], 'paused', ARGV[1], 'changedAt', ARGV[2])
return 1
