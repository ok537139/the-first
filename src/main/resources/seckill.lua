-- 秒杀资格判断 + 预扣库存
-- KEYS[1] 库存 key       seckill:stock:{voucherId}
-- KEYS[2] 已购用户集合   seckill:order:{voucherId}
-- KEYS[3] 活动时间 Hash  seckill:time:{voucherId}（begin / end，毫秒时间戳）
-- ARGV[1] 用户 id
-- ARGV[2] 当前时间（毫秒）
-- 返回：0 成功，1 库存不足，2 重复下单，3 活动不存在，4 活动未开始，5 活动已结束
-- 所有 key 通过 KEYS 传入；Redis Cluster 下还需要给 key 加 hash tag，保证落在同一个 slot。

-- 1.活动是否存在（库存未预热时 GET 返回 false，直接比较会抛 Lua 错误）
local stock = redis.call('get', KEYS[1])
if not stock then
    return 3
end

-- 2.活动时间
local now = tonumber(ARGV[2])
local time = redis.call('hmget', KEYS[3], 'begin', 'end')
if time[1] and now < tonumber(time[1]) then
    return 4
end
if time[2] and now > tonumber(time[2]) then
    return 5
end

-- 3.库存
if tonumber(stock) <= 0 then
    return 1
end

-- 4.一人一单
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    return 2
end

-- 5.判断全部通过后再写：Lua 出错不会回滚已执行的写命令
redis.call('incrby', KEYS[1], -1)
redis.call('sadd', KEYS[2], ARGV[1])
-- 原 Redis Stream 方案在这里 XADD 发消息，现改为 Java 中发送 Kafka 消息
-- redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0

