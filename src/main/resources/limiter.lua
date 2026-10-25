--[[
    【滑动窗口限流】-3
    【关键点】 Lua脚本中实现的滑动窗口限流算法（滑动日志：每个请求一条记录）
    利用Redis的ZSet数据类型，首先传递进Lua脚本的参数为
        ● 限流键名key (不同的接口/资源，使用不同的key，不同的维度，也要使用不同的key)
        ● 时间窗口大小 window
        ● 限流阈值 limit  在时间窗口内允许访问的次数
        ● 本次请求的唯一 id，作为 ZSet 的 member
    当前时间由 Redis TIME 命令获取，多个应用实例共用 Redis 的时钟，不受各机器时钟偏差影响
    （Redis 5 起脚本按效果复制，调用 TIME 之后仍可执行写命令）
    进入Lua脚本的逻辑
        1. 删除超出时间窗口的数据 redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
        2. 获取当前时间窗口内的已请求数量 redis.call('ZCARD', key)
        3. 判断当前时间窗口内的已请求数量 是否 小于 limit 限制次数
            a. 若小于，则把本次请求加入key中，以当前时间戳为分数score，以请求唯一 id 为成员member，更新过期时间，本次请求可放行
            b. 若大于或等于，则返回本次请求应该限流
]]

local key = KEYS[1]
local window = tonumber(ARGV[1]) -- 时间窗口(单位为秒)
local limit = tonumber(ARGV[2]) -- 时间窗口内限制的次数
local member = ARGV[3]          -- 本次请求的唯一 id

-- 校验传进来的参数是否为空,若为空直接返回错误
if not window or not limit or not member then
  return redis.error_reply("Invalid input parameters")
end

-- 当前时间（毫秒）：TIME 返回 {秒, 微秒}
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

window = window * 1000  -- 单位由秒->毫秒

-- 删除超出时间窗口的数据：删除所有分数在 [0, now-window] 的请求记录
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 获取当前窗口内的请求数量
local current = redis.call('ZCARD', key)

-- 若"当前时间窗口内的请求数量" 小于 "时间窗口内限制的次数"
if current < limit then
  -- 添加当前请求。member 必须唯一：ZSet 成员去重，相同 member 只会更新分数，请求会少计
  redis.call('ZADD', key, now, member)
  -- 更新过期时间
  redis.call('EXPIRE', key, window / 1000)
  return current + 1
else
  -- 若"当前时间窗口内的请求数量" 大于等于 "时间窗口内限制的次数", 则应该限制本次请求,返回 0,表示限流
  return 0
end
