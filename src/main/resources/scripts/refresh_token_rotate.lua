-- KEYS[1] = 옛 Refresh Token 키(refreshToken:{옛 토큰 해시})
-- KEYS[2] = 새 Refresh Token 키(refreshToken:{새 토큰 해시})
-- ARGV[1] = 새 토큰 TTL(ms)
--
-- Opaque 토큰이라 토큰 자체에는 아무 정보가 없다. 그래서 원자적 CAS 의 모양이
-- "고정된 슬롯의 값이 옛 해시와 같으면 새 해시로 교체" 가 아니라, 키 자체가 토큰마다 다르므로
-- "옛 토큰 키가 있으면 그 값(id|role|remember)을 새 토큰 키로 옮기고 옛 키를 정리한다" 가 된다.
--
-- 옛 키를 곧바로 DEL 하지 않는다. 그 죽은 토큰이 나중에 재생되면 재사용 탐지는 되지만,
-- 지워 버렸다면 그것이 누구 것이었는지 알 수 없어 그 계정의 다른 세션을 끊을 수가 없다.
-- 기능 명세서 94행이 "폐기된 Refresh 를 다시 쓰면 그 계정의 전체 세션을 종료한다" 고 요구하므로
-- 소유자 정보가 남아 있어야 한다. 그래서 값 뒤에 |REVOKED 마커를 붙여 tombstone 으로 남긴다.
--
-- tombstone 상태에서는 회전이 다시 성공해서는 안 된다. 재사용 시도가 새 유효 토큰을 만들어내면
-- 안 되기 때문이다. 그래서 마커가 있으면 값만 그대로 돌려주고 상태를 바꾸지 않는다.
--
-- tombstone 의 유효기간은 따로 정하지 않고 옛 키에 남아 있던 TTL(PTTL)을 그대로 쓴다.
-- 1회용 토큰이 원래 유효했을 남은 시간 동안은 언제든 재생될 수 있으니, tombstone 도 그만큼은
-- 살아 있어야 재사용 탐지가 새지 않는다. 고정된 짧은 유예로는 그보다 늦은 시도를 못 잡는다.
--
-- Redis 가 싱글스레드라 이 스크립트 전체가 원자적으로 실행된다. 같은 옛 토큰으로 재발급 요청이
-- 동시에 둘 들어와도 하나만 정상 회전에 성공한다.
--
-- 반환값 셋
--   false                        : 이 키가 원래 없었거나 tombstone 까지 자연 만료됐다
--   "id|role|remember"           : 정상 회전 성공(최초 사용)
--   "id|role|remember|REVOKED"   : 재사용 탐지. 소유자 정보를 담아 돌려주고 회전은 실패로 둔다

local value = redis.call('GET', KEYS[1])
if not value then
    return false
end

if string.sub(value, -8) == '|REVOKED' then
    return value
end

local remainingTtl = redis.call('PTTL', KEYS[1])

redis.call('SET', KEYS[2], value, 'PX', ARGV[1])
if remainingTtl > 0 then
    redis.call('SET', KEYS[1], value .. '|REVOKED', 'PX', remainingTtl)
else
    -- PTTL 이 0 이나 음수로 나오는 것은 이론상 거의 없다. 방금 GET 으로 값을 읽었으니 그 순간엔
    -- 살아 있었다는 뜻이라 TTL 도 양수였어야 한다. 그래도 방어적으로, TTL 을 못 구하면
    -- tombstone 없이 지운다.
    redis.call('DEL', KEYS[1])
end
return value
