if redis.call('HGET', KEYS[1], 'version') == ARGV[1] then
    return redis.call('ZREM', KEYS[2], ARGV[2])
end
return 0
