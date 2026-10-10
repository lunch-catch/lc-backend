-- 차단 기준 순번은 증가하는 방향으로만 갱신하며, 서버 시간이 뒤로 바뀌는 상황이 발생해도 기존 토큰을 다시 허용하지 않도록 차단 기록을 자동으로 삭제하지 않는다.
local current = redis.call('GET', KEYS[1])
if current and current >= ARGV[1] then return 0 end
redis.call('SET', KEYS[1], ARGV[1])
return 1
