-- KEYS[1]: 당일 소진 해시 billing:day:{businessDate}:{campaignId}
-- ARGV: 예약액, 당일 소진, 중단 여부(1 또는 0), 변경 시각(epoch 마이크로초), 해시 TTL(초)
-- 반환: 만들었으면 1, 이미 있어서 아무것도 하지 않았으면 0
--
-- 해시가 없을 때만 네 필드를 한 번에 쓴다. 이미 있으면 덮어쓰지 않는다.
-- 동시에 여러 요청이 재구성해도 첫 번째만 쓰고 나머지는 그 값을 그대로 쓴다.
-- 이미 있는 해시는 그 뒤의 차감이 늘린 소진을 담고 있을 수 있어서 DB 에서 읽은 값으로 되돌리면 안 된다.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
redis.call('HSET', KEYS[1], 'reserved', ARGV[1], 'spend', ARGV[2], 'paused', ARGV[3], 'changedAt', ARGV[4])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
return 1
