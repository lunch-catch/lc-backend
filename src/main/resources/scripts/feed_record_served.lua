-- 응답한 카드의 캠페인을 하루 횟수와 세션 집합에 함께 올린다. 한 번에 보내 둘 사이에 끊겨 한쪽만 남지 않게 한다.
-- KEYS: 1=하루 해시, 2=세션 집합
-- ARGV: 1=하루 해시의 TTL(ms), 2=세션 TTL(ms), 3 부터=캠페인 ID
-- 만료를 시각이 아니라 남은 시간으로 받는다. 앱의 시계로 정한 영업일과 Valkey 의 시계가 어긋나도 키가 곧바로 사라지지 않는다
for i = 3, #ARGV do
    redis.call('HINCRBY', KEYS[1], ARGV[i], 1)
    redis.call('SADD', KEYS[2], ARGV[i])
end
if #ARGV >= 3 then
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
-- 카드가 없는 요청도 활동이라 세션을 늘린다. 키가 없으면 아무 일도 없다
redis.call('PEXPIRE', KEYS[2], ARGV[2])
return 1
