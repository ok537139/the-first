return redis.call('ZREVRANGEBYLEX', KEYS[1], ARGV[1], '-', 'LIMIT', 0, ARGV[2])
