-- KEYS[1]: 당일 소진 해시 billing:day:{businessDate}:{campaignId}
-- KEYS[2]: 처리 표시 billing:served:{serveId}
-- ARGV[1]: 단가
-- 반환: 되돌렸으면 1, 처리 표시가 없어 되돌릴 것이 없었으면 0
--
-- 노출 저장 트랜잭션이 롤백된 뒤, 그 트랜잭션이 새로 남긴 판정을 지운다. billing_deduct.lua 의 반대다.
-- 처리 표시를 지우고, 그 판정이 BILLED 였으면 올렸던 소진을 단가만큼 내린다.
-- 처리 표시가 없으면 아무것도 하지 않는다. 같은 보상이 두 번 불려도 소진이 두 번 내려가지 않고,
-- 표시가 이미 만료됐거나 해시와 함께 사라졌다면 DB 정본으로 만든 값에 이 노출은 들어 있지 않다.
-- 해시가 없으면 소진은 건드리지 않는다.
local served = redis.call('GET', KEYS[2])
if not served then
    return 0
end
redis.call('DEL', KEYS[2])
if served == 'BILLED' and redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('HINCRBY', KEYS[1], 'spend', -tonumber(ARGV[1]))
end
return 1
