-- 订单没有建成时，撤销 seckill.lua 的预扣，用户可以重新抢
-- KEYS[1] 库存 key，KEYS[2] 已购用户集合
-- ARGV[1] 用户 id
-- ARGV[2] '1'：加回库存（消息没发出去，或消费重试用尽）
--         '0'：不加回，并把 Redis 库存清零（MySQL 已经没有库存，说明 Redis 库存偏多）
-- 只有用户确实在已购集合中时才处理，重复执行是安全的
if redis.call('srem', KEYS[2], ARGV[1]) == 0 then
    return 0
end
if ARGV[2] == '1' then
    redis.call('incrby', KEYS[1], 1)
else
    redis.call('set', KEYS[1], 0)
end
return 1
