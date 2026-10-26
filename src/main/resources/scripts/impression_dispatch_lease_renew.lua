-- 릴레이 점유를 내 점유일 때만 연장한다. 다른 서버가 이미 가져갔으면 0 을 돌려준다(#1010).
-- KEYS: 1=점유 키
-- ARGV: 1=내 서버 id, 2=만료(ms)
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 0
