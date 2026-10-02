-- KEYS[1] = 대상 Refresh Token 기본 키(refreshToken:{tokenHash})
-- KEYS[2] = 그 계정의 현재 토큰 포인터(activeRefreshToken:{role}:{id})
-- ARGV[1] = 대상 tokenHash
--
-- 기본 키는 대상 토큰이므로 항상 지운다. 반면 포인터는 그 사이 새 로그인이나 회전으로 다른
-- 해시를 가리킬 수 있어, 대상 해시와 일치할 때만 지운다. 같은 Lua 실행 안에서 처리해
-- GET 비교와 DEL 사이에 다른 요청이 끼어들 수 없다.
redis.call('DEL', KEYS[1])

if redis.call('GET', KEYS[2]) == ARGV[1] then
    redis.call('DEL', KEYS[2])
end

return 1
