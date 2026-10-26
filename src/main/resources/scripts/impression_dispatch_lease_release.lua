-- 릴레이 점유를 내 점유일 때만 푼다. 만료 뒤 다른 서버가 가져간 점유를 지우지 않는다(#1010).
-- KEYS: 1=점유 키
-- ARGV: 1=내 서버 id
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
