-- KEYS: 토큰 기본 키, 활성 포인터, 발급 순번 키
-- ARGV: 소유자 정보, 토큰 해시, TTL(ms), 19자리 0 패딩 발급 순번
-- 순번은 문자열로 비교해 Lua 숫자의 53비트 정밀도 제한을 피한다.
local current = redis.call('GET', KEYS[3])
if current and current >= ARGV[4] then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
redis.call('SET', KEYS[3], ARGV[4])
return 1
