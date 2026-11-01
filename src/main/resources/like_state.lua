-- All writes, version changes and retry registration are one Redis operation.
local ready = redis.call('HGET', KEYS[2], 'version')
if not ready then
    if ARGV[5] ~= '1' then return {-1, 0} end
    local members = redis.call('ZCARD', KEYS[1])
    local baseline = math.max(0, tonumber(ARGV[6]) - members)
    redis.call('HSET', KEYS[2], 'baseline', baseline, 'version', 0, 'members', members)
end
-- Missing/evicted identity data cannot be reconstructed from a count. Fail closed.
local size = redis.call('ZCARD', KEYS[1])
if size ~= tonumber(redis.call('HGET', KEYS[2], 'members')) then return {-1, 0} end
local changed = 0
if ARGV[2] == '1' then
    changed = redis.call('ZADD', KEYS[1], 'NX', ARGV[3], ARGV[1])
else
    changed = redis.call('ZREM', KEYS[1], ARGV[1])
end
if changed > 0 then redis.call('HINCRBY', KEYS[2], 'version', 1) end
size = redis.call('ZCARD', KEYS[1])
redis.call('HSET', KEYS[2], 'members', size)
-- Register even an unchanged request: it can repair a previous uncertain response.
redis.call('ZADD', KEYS[3], 'NX', ARGV[3], ARGV[4])
return {tonumber(redis.call('HGET', KEYS[2], 'baseline')) + size, tonumber(ARGV[2])}
