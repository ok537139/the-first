local version = redis.call('HGET', KEYS[2], 'version')
if not version then return {} end
local size = redis.call('ZCARD', KEYS[1])
if size ~= tonumber(redis.call('HGET', KEYS[2], 'members')) then return {} end
local liked = 0
if ARGV[1] ~= '' and redis.call('ZSCORE', KEYS[1], ARGV[1]) then liked = 1 end
return {tonumber(version), tonumber(redis.call('HGET', KEYS[2], 'baseline')) + size, liked}
