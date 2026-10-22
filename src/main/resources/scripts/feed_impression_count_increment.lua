-- 그날 캠페인의 유효 노출 수를 1 올리고 만료를 건다. 한 번에 보내 올렸는데 만료가 없는 키가 남지 않게 한다(#783).
-- KEYS: 1=그날 해시
-- ARGV: 1=TTL(ms), 2=캠페인 ID
redis.call('HINCRBY', KEYS[1], ARGV[2], 1)
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return 1
