redis.call('ZADD', KEYS[1], 0, ARGV[1])
local start = redis.call('ZSCORE', KEYS[1], '!') and 1 or 0
redis.call('ZREMRANGEBYRANK', KEYS[1], start, -tonumber(ARGV[2])-1)
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
