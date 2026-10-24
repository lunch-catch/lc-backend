-- 세션별 발급 순번을 비교하고 토큰·활성 포인터·순번을 함께 게시한다.
local current = redis.call('GET', KEYS[3])
if current and current >= ARGV[4] then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
redis.call('SET', KEYS[3], ARGV[4], 'PX', ARGV[3])
return 1
