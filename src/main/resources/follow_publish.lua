redis.call('RENAME', KEYS[1], KEYS[2])
redis.call('EXPIRE', KEYS[2], ARGV[2])
redis.call('SET', KEYS[3], ARGV[1], 'EX', ARGV[2])
redis.call('DEL', KEYS[4])
return 1
