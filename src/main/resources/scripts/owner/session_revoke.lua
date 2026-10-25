-- 세션별 순번을 올려 지연 RT 게시를 막고 같은 세션의 AT도 차단한다.
local version = redis.call('GET', KEYS[2])
if not version or version <= ARGV[1] then
    local active = redis.call('GET', KEYS[1])
    if active then redis.call('DEL', ARGV[3] .. active) end
    redis.call('DEL', KEYS[1])
    redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[4])
end
redis.call('DEL', ARGV[3] .. ARGV[2])
redis.call('SET', KEYS[3], '1', 'PX', ARGV[4])
return 1
