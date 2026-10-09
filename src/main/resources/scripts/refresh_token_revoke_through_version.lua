-- 폐기 순번 이하의 요청이 RT를 다시 저장하지 못하게 하고, Redis의 현재 발급 순번도 폐기 순번 이하이면 현재 RT를 삭제한다.
-- 더 높은 발급 순번의 로그인에서 저장한 현재 RT 정보는 삭제하지 않는다.
local version = redis.call('GET', KEYS[2])
if not version or version <= ARGV[1] then
    local active = redis.call('GET', KEYS[1])
    if active then redis.call('DEL', ARGV[3] .. active) end
    redis.call('DEL', KEYS[1])
    redis.call('SET', KEYS[2], ARGV[1])
end
if ARGV[2] ~= '' then redis.call('DEL', ARGV[3] .. ARGV[2]) end
return 1
