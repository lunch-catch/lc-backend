-- 호출한 서비스가 DB의 점주 행을 잠그고 기존 RT가 여전히 유효한지 확인한 경우에만 다시 사용할 수 있게 한다.
local value = redis.call('GET', KEYS[1])
if value and string.sub(value, -8) == '|PENDING' then
    redis.call('SET', KEYS[1], string.sub(value, 1, -9), 'KEEPTTL')
    return 1
end
return 0
