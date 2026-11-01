-- Atomic cache invalidation and GEO move. Retries always use current MySQL data.
local previous = redis.call('HGET', KEYS[2], ARGV[1])
redis.call('DEL', KEYS[1])
if previous then redis.call('ZREM', ARGV[2] .. previous, ARGV[1]) end
if ARGV[3] ~= '0' then redis.call('ZREM', ARGV[2] .. ARGV[3], ARGV[1]) end
if ARGV[5] ~= '' and ARGV[6] ~= '' then
    redis.call('GEOADD', ARGV[2] .. ARGV[4], ARGV[5], ARGV[6], ARGV[1])
end
redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
return 1
