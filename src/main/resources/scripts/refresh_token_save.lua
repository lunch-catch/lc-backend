-- KEYS[1] = 새 Refresh Token 기본 키(refreshToken:{tokenHash})
-- KEYS[2] = 그 계정의 현재 토큰 포인터(activeRefreshToken:{role}:{id})
-- ARGV[1] = 기본 레코드 값(id|role|remember)
-- ARGV[2] = tokenHash
-- ARGV[3] = TTL(ms)
--
-- 기본 레코드와 활성 포인터는 함께 최신 토큰을 나타낸다. 둘을 별도 Redis 명령으로 쓰면
-- 첫 번째 성공 뒤 두 번째가 실패했을 때 서로 다른 토큰을 가리킬 수 있으므로 한 Lua 실행으로
-- 저장한다.
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])

return 1
