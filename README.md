# 好味杭州（hm-dianping）

前后端分离的本地生活服务演示项目。后端为 Spring Boot，前端位于 `frontend/`，使用 React、TypeScript 和 Vite。

## 启动

1. 启动 MySQL、Redis、Kafka：

   ```powershell
   docker compose up -d
   ```

2. 使用 JDK 8 启动后端：

   ```powershell
   $env:JAVA_HOME='D:\Develop\Java\jdk-8'
   $env:Path="$env:JAVA_HOME\bin;$env:Path"
   mvn spring-boot:run
   ```

3. 启动前端：

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

打开 <http://127.0.0.1:5174>。前端开发服务器通过 `/api` 代理到 <http://127.0.0.1:8081>。

## 演示数据

`src/main/resources/db/demo-data.sql` 会在新建 MySQL 数据卷时自动执行。已有数据卷可手动执行：

```powershell
Get-Content -Raw -Encoding UTF8 .\src\main\resources\db\demo-data.sql | docker exec -i hm-dianping-mysql mysql -uroot -p123456 --default-character-set=utf8mb4
```

脚本可重复执行，包括 22 家商户、用户资料、关注关系、优惠券、秒杀库存、订单与评论数据。

2026-10-12 起 `hmdp.sql` 增加了唯一索引和评论索引。已有数据卷需要执行一次 `upgrade-indexes.sql`（会先清理重复的关注记录）：

```powershell
Get-Content -Raw -Encoding UTF8 .\src\main\resources\db\upgrade-indexes.sql | docker exec -i hm-dianping-mysql mysql -uroot -p123456 hmdp --default-character-set=utf8mb4
```

## 本次数据库升级

2026-10-23 的一致性改造**不新增表**，在原表增加补偿状态、版本和索引。已有数据库先停止旧后端，再执行一次：

```powershell
Get-Content -Raw -Encoding UTF8 ./src/main/resources/db/upgrade-consistency.sql | docker exec -i hm-dianping-mysql mysql -uroot -p123456 --default-character-set=utf8mb4
```

新建 Docker 数据卷按顺序执行 `hmdp.sql` → `demo-data.sql` → `upgrade-consistency.sql`，无需手动执行。已有数据卷不会自动补跑；升级脚本只执行一次，不要用原始建表脚本覆盖已有库。保留已有的 `uk_user_follow(user_id,follow_user_id)` 唯一索引。

## 构建

```powershell
mvn -DskipTests compile
cd frontend
npm run build
```

## MySQL 表结构

基础建表脚本为 `src/main/resources/db/hmdp.sql`，新增字段和索引见 `upgrade-consistency.sql`；仍共 10 张表，引擎 InnoDB，字符集 `utf8mb4`。表之间没有外键约束，关联由应用层维护。

### 关系概览

```
tb_user ──1:1── tb_user_info
   │
   ├──< tb_blog >── tb_shop ──< tb_voucher ──1:1── tb_seckill_voucher
   │       │            │
   │       │         tb_shop_type
   │       └──< tb_blog_comments（自引用：parent_id）
   │
   ├──< tb_follow（user_id 关注 follow_user_id）
   └──< tb_voucher_order >── tb_voucher
```

### tb_user（用户）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint UNSIGNED | 否 | 自增 | 主键 |
| `phone` | varchar(11) | 否 | | 手机号码（唯一索引 `uniqe_key_phone`） |
| `password` | varchar(128) | 是 | '' | 密码，加密存储 |
| `nick_name` | varchar(32) | 是 | '' | 昵称，默认是用户 id |
| `icon` | varchar(255) | 是 | '' | 人物头像 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |
| `follow_version` | bigint | 否 | 0 | 当前关注关系版本；关注/取关成功时递增 |
| `follow_sync_pending` | tinyint | 否 | 0 | 1 表示关注缓存待同步；与关系变更在同一个事务提交 |
| `follow_retry_at` | datetime(3) | 否 | CURRENT_TIMESTAMP(3) | 下次允许补偿的时间 |

索引：`idx_user_follow_due(follow_sync_pending,follow_retry_at,id)`，只查到期的待同步用户。


### tb_user_info（用户扩展信息，与 tb_user 一对一）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `user_id` | bigint UNSIGNED | 否 | | 主键，用户 id |
| `city` | varchar(64) | 是 | '' | 城市名称 |
| `introduce` | varchar(128) | 是 | NULL | 个人介绍，不超过 128 个字符 |
| `fans` | int UNSIGNED | 是 | 0 | 粉丝数量 |
| `followee` | int UNSIGNED | 是 | 0 | 关注的人的数量 |
| `gender` | tinyint UNSIGNED | 是 | 0 | 0 男，1 女 |
| `birthday` | date | 是 | NULL | 生日 |
| `credits` | int UNSIGNED | 是 | 0 | 积分 |
| `level` | tinyint UNSIGNED | 是 | 0 | 会员级别 0~9，0 代表未开通会员 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |

### tb_follow（关注关系）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint UNSIGNED | 否 | | 用户 id |
| `follow_user_id` | bigint UNSIGNED | 否 | | 被关注的用户 id（与 `user_id` 组成唯一索引 `uk_user_follow`） |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |

索引：`uk_user_follow(user_id,follow_user_id)` 用于去重和按用户恢复；`idx_follow_fanout(follow_user_id,id,user_id)` 用于按作者分批查询粉丝。

### tb_shop_type（商铺类型）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint UNSIGNED | 否 | 自增 | 主键 |
| `name` | varchar(32) | 是 | NULL | 类型名称 |
| `icon` | varchar(255) | 是 | NULL | 图标 |
| `sort` | int UNSIGNED | 是 | NULL | 顺序 |
| `create_time` | timestamp | 是 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 是 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |

### tb_shop（商铺）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint UNSIGNED | 否 | 自增 | 主键 |
| `name` | varchar(128) | 否 | | 商铺名称 |
| `type_id` | bigint UNSIGNED | 否 | | 商铺类型 id（普通索引 `foreign_key_type`） |
| `images` | varchar(1024) | 否 | | 商铺图片，多个图片以 `,` 隔开 |
| `area` | varchar(128) | 是 | NULL | 商圈，例如陆家嘴 |
| `address` | varchar(255) | 否 | | 地址 |
| `x` | double UNSIGNED | 否 | | 经度 |
| `y` | double UNSIGNED | 否 | | 纬度 |
| `avg_price` | bigint UNSIGNED | 是 | NULL | 均价，取整数 |
| `sold` | int UNSIGNED | 否 | | 销量 |
| `comments` | int UNSIGNED | 否 | | 评论数量 |
| `score` | int UNSIGNED | 否 | | 评分，1~5 分，乘 10 保存，避免小数 |
| `open_hours` | varchar(32) | 是 | NULL | 营业时间，例如 10:00-22:00 |
| `create_time` | timestamp | 是 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 是 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |
| `cache_sync_stage` | tinyint | 否 | 0 | 0 完成；1 等待首次删缓存/GEO同步；2 等待第二次删除 |
| `cache_retry_at` | datetime(3) | 否 | CURRENT_TIMESTAMP(3) | 下一次删除或失败重试时间 |
| `cache_geo_type_id` | bigint | 否 | 0 | 上次同步的商户类型，用于移除旧 GEO 分组 |

索引：`idx_shop_cache_due(cache_sync_stage,cache_retry_at,id)`。新增/修改商户与阶段标记在同一个事务提交，重启后继续执行双删。


### tb_blog（探店笔记）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint UNSIGNED | 否 | 自增 | 主键 |
| `shop_id` | bigint | 否 | | 商户 id |
| `user_id` | bigint UNSIGNED | 否 | | 用户 id |
| `title` | varchar(255) | 否 | | 标题 |
| `images` | varchar(2048) | 否 | | 探店的照片，最多 9 张，以 `,` 隔开 |
| `content` | varchar(2048) | 否 | | 探店的文字描述 |
| `liked` | int UNSIGNED | 是 | 0 | 点赞数量 |
| `comments` | int UNSIGNED | 是 | NULL | 评论数量 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |
| `feed_mode` | tinyint | 否 | 0 | 0 推送；1 拉取；每篇发布时确定，后续不随粉丝数改变 |
| `feed_status` | tinyint | 否 | 0 | 0 已完成；1 等待投递；新笔记事务内设为 1 |
| `feed_cursor` | bigint | 否 | 0 | 已处理的最后一个关注记录 id；分批续传 |
| `feed_retry_at` | datetime(3) | 否 | CURRENT_TIMESTAMP(3) | 下次投递时间 |
| `like_initialized` | tinyint | 否 | 0 | 是否初始化过新版点赞状态；初始化后 Redis 元数据缺失时停止写入，避免静默清零 |

索引：`idx_blog_feed_due(feed_status,feed_retry_at,id)` 查待投递记录；`idx_blog_author_feed(user_id,feed_mode,create_time,id)` 查某作者某种投递方式的近期笔记；`idx_blog_author_time(user_id,create_time,id)` 支持作者列表和故障降级。


### tb_blog_comments（笔记评论）

评论为两级结构：一级评论的 `parent_id` 为 0；回复的 `parent_id` 指向所属的一级评论，`answer_id` 记录被回复的评论。索引：`idx_blog_parent_status_time(blog_id, parent_id, status, create_time)`、`idx_parent_time(parent_id, create_time)`。

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint UNSIGNED | 否 | 自增 | 主键 |
| `user_id` | bigint UNSIGNED | 否 | | 用户 id |
| `blog_id` | bigint UNSIGNED | 否 | | 探店 id |
| `parent_id` | bigint UNSIGNED | 否 | | 关联的一级评论 id，一级评论为 0 |
| `answer_id` | bigint UNSIGNED | 否 | | 回复的评论 id |
| `content` | varchar(255) | 否 | | 评论内容 |
| `liked` | int UNSIGNED | 是 | NULL | 点赞数 |
| `status` | tinyint UNSIGNED | 是 | NULL | 0 正常，1 被举报，2 禁止查看，3 已删除 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |
| `like_initialized` | tinyint | 否 | 0 | 与笔记相同，标记点赞状态是否初始化 |
| `like_cleanup_pending` | tinyint | 否 | 0 | 删除评论时设为 1，后台完成 Redis 点赞清理后归零 |

索引：`idx_comment_like_cleanup(like_cleanup_pending,id)`。删除改为 `status=3`，保留原行用于可靠清理；列表与点赞只接受 `status=0`。


### tb_voucher（优惠券）

`type` 为 1 的是秒杀券，其库存和时间存放在 `tb_seckill_voucher`。

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint UNSIGNED | 否 | 自增 | 主键 |
| `shop_id` | bigint UNSIGNED | 是 | NULL | 商铺 id |
| `title` | varchar(255) | 否 | | 代金券标题 |
| `sub_title` | varchar(255) | 是 | NULL | 副标题 |
| `rules` | varchar(1024) | 是 | NULL | 使用规则 |
| `pay_value` | bigint UNSIGNED | 否 | | 支付金额，单位分 |
| `actual_value` | bigint | 否 | | 抵扣金额，单位分 |
| `type` | tinyint UNSIGNED | 否 | 0 | 0 普通券，1 秒杀券 |
| `status` | tinyint UNSIGNED | 否 | 1 | 1 上架，2 下架，3 过期 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |

### tb_seckill_voucher（秒杀券，与优惠券一对一）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `voucher_id` | bigint UNSIGNED | 否 | | 主键，关联的优惠券 id |
| `stock` | int | 否 | | 库存 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `begin_time` | timestamp | 否 | '0000-00-00 00:00:00' | 生效时间 |
| `end_time` | timestamp | 否 | '0000-00-00 00:00:00' | 失效时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |

### tb_voucher_order（券订单）

| 字段 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | bigint | 否 | 无（程序生成） | 主键，由 `RedisIdWorker` 生成 |
| `user_id` | bigint UNSIGNED | 否 | | 下单的用户 id |
| `voucher_id` | bigint UNSIGNED | 否 | | 购买的代金券 id（与 `user_id` 组成唯一索引 `uk_user_voucher`，一人一单的数据库兜底） |
| `pay_type` | tinyint UNSIGNED | 否 | 1 | 1 余额支付，2 支付宝，3 微信 |
| `status` | tinyint UNSIGNED | 否 | 1 | 1 未支付，2 已支付，3 已核销，4 已取消，5 退款中，6 已退款 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 下单时间 |
| `pay_time` | timestamp | 是 | NULL | 支付时间 |
| `use_time` | timestamp | 是 | NULL | 核销时间 |
| `refund_time` | timestamp | 是 | NULL | 退款时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP，更新时自动刷新 | 更新时间 |

## Redis 数据结构

Redis 单机部署（`localhost:6379`），通用 key 前缀位于 `RedisConstants.java`，一致性和推拉 Feed 的 key 位于 `service/consistency/`。以下 TTL 是默认配置。

| Key | 类型 | 过期 | 存储内容与用途 |
|---|---|---|---|
| `login:code:{phone}` | String | 2 分钟 | 登录验证码，登录成功后删除 |
| `login:code:limit:{phone}` | String | 60 秒 | 验证码发送间隔，存在时不能再次发送 |
| `login:code:fail:{phone}` | String | 2 分钟 | 验证码校验失败次数，达到 5 次作废验证码 |
| `login:token:{token}` | Hash | 36000 分钟，每次请求刷新 | 登录态，字段为 `UserDTO` 的 `id`、`nickName`、`icon` |
| `sign:{userId}:{yyyyMM}` | String（Bitmap） | 无 | 用户当月签到记录，第 N 位表示第 N 天 |
| `cache:shop:{id}` | String（JSON） | 30 分钟，空值 2 分钟 | 商铺缓存，空值用于防止缓存穿透 |
| `lock:shop:{id}` | String | 10 秒 | 缓存重建互斥锁，使用随机令牌和 Lua 校验释放 |
| `shop:geo:{typeId}` | GEO | 无 | 按商铺类型分组的坐标，member 为商铺 id，用于附近商铺查询；启动登记同步任务，新增/修改时同步坐标并移除旧类型成员 |
| `shop:geo:types` | Hash | 无 | shopId → 上次写入 Redis 的 typeId，配合 MySQL 记录清理旧 GEO 分组 |
| `bloom:shop:current` / `bloom:shop:building` | String | 版本切换管理 | 当前/构建中的店铺布隆过滤器 key |
| `bloom:shop:v{version}` | Redisson Bloom（位图与配置） | 版本切换清理 | 存在的店铺 id，启动检查和每日低峰重建 |
| `lock:bloom:shop` | Redisson Lock | 看门狗管理 | 防止多个实例同时重建过滤器 |
| `cache:voucher:list:{shopId}` | String（JSON） | 30 分钟 | 商铺优惠券列表缓存，`hmdp.cache.voucher-list.mode` 为 `redis` 或 `caffeine` 时使用（当前 `caffeine`）。新增秒杀券时在事务提交后删除；返回前用 `seckill:stock:*` 覆盖为实时库存 |
| `seckill:stock:{voucherId}` | String | 无 | 秒杀券库存，由 Lua 脚本原子扣减 |
| `seckill:order:{voucherId}` | Set | 无 | 已抢到该秒杀券的用户 id，用于一人一单；启动时按订单表回填；订单最终没建成时移出 |
| `seckill:time:{voucherId}` | Hash | 无 | 秒杀活动时间，字段 `begin`、`end` 为毫秒时间戳，由 Lua 校验；新增券时在事务提交前写入，启动时回填 |
| `icr:order:{yyyy:MM:dd}` | String（计数器） | 无 | 订单 id 的当日自增序列 |
| `blog:liked:{blogId}` | ZSet | 无 | 笔记点赞名单，member 为 userId，score 为点赞时间戳 |
| `blog:comment:liked:{commentId}` | ZSet | 无 | 评论点赞名单，member 为 userId，score 为点赞时间戳 |
| `likes:meta:blog:{id}` / `likes:meta:comment:{id}` | Hash | 无 | `baseline` 历史计数基数、`members` 当前名单人数、`version` 状态版本 |
| `likes:dirty` | ZSet | 无 | 待同步目标 `blog:{id}` / `comment:{id}`，score 为下次重试的毫秒时间 |
| `feed:inbox:{userId}` | ZSet | 20 分钟，写入时刷新 | 普通作者的推送收件箱，最多 1000 篇，加一个加载哨兵 `!` |
| `feed:ready:{userId}` | String | 10 分钟 | 收件箱已补齐的关注版本，与 MySQL `follow_version` 比较 |
| `feed:outbox:{authorId}` | ZSet | 20 分钟，写入时刷新 | 大作者发件箱，最多 1000 篇，加一个加载哨兵 `!` |
| `feed:outbox:ready:{authorId}` | String | 10 分钟 | 大作者发件箱已加载标记 |
| `follows:{userId}` | Set | 10 分钟 | 关注的作者 id，加哨兵 `_` 区分空集合与缺失；共同关注时排除哨兵 |
| `follows:version:{userId}` | String | 10 分钟 | 对应 MySQL 关注版本，防止读取旧集合 |
| `follows:building:{userId}:{uuid}` | Set | 60 秒 | 临时重建集合，完成后 Lua 原子替换正式集合和版本 |
| `{limit key}{类名}:{方法名}[:ip:{ip} 或 :user:{id}]` | ZSet | 窗口秒数 | 滑动窗口限流记录，由 `@RateLimiter` 生成，时间取 Redis `TIME`。用于秒杀接口（`rate_limit:seckill:`，全局 + 按用户）和 `/shop/of/type`（`rate_limit:shop:`，按 IP，5 秒 10 次） |

### 一致性怎么处理

- **商户**：MySQL 更新与待同步标记一起提交；提交后立即删详情缓存并同步 GEO，约 1 秒后再次删除。失败保留阶段，后台按索引每批 100 条重试。新增商户还会先写布隆过滤器；该步骤失败时数据库事务回滚。双删降低旧缓存回填风险，不提供跨库强事务。
- **关注**：MySQL 保存关系和新版本；Redis 写失败不撤销已提交的关注关系。后台只处理待同步用户，按 `user_id` 和索引分批查询，读取时也校验版本，缺失则重建；Redis 不可用时直接查该用户的 MySQL 关系。
- **点赞**：请求必须明确 `liked=true/false`。请求只做一次不加锁的主键查询，Lua 一次修改名单、版本和待同步集合；后台锁定对应数据库行，读 Redis 当前计数，绝对赋值写入 MySQL，提交后仅在版本未变时清理任务。失败重试不会再次加减。笔记和评论列表优先显示 Redis 当前计数，热门排序仍依赖 MySQL 同步后的计数。
- **已有点赞**：首次接入保留 `max(MySQL计数 - Redis名单人数, 0)` 作为历史基数；不伪造用户。初始化之后发现元数据或名单异常，拒绝继续写入；Redis 名单及备份同时丢失时，MySQL 计数无法恢复“谁点过赞”。Redis 使用 AOF，点赞键不可主动设置过期；部署应使用 `noeviction`，并保留备份。AOF/备份的丢失窗口仍适用。
- **秒杀资格**：订单最终没建成时退回资格（移出 `seckill:order`）。消息发送失败、消费重试用尽进入死信且库里确实没有订单时，同时加回 Redis 库存；消费者发现 MySQL 库存不足时不加回，并把 Redis 库存清零。死信只用于排查，资格已退回，不要原样重放。
- **删除评论**：在 MySQL 标记删除和待清理，后台删 Redis 点赞名单与元数据后清除标记；不会再被点赞同步任务写回有效评论。

### 推拉结合与分页

- 发布时粉丝数达到 `hmdp.consistency.big-author-threshold`（默认 10000）走拉取：写作者发件箱。否则按 `tb_follow.id` 每批 500 个粉丝推送收件箱。发布内容和待投递标记在同一数据库事务保存。
- 读取合并用户收件箱与所关注大作者的发件箱，去重并过滤已取关作者。每篇笔记的 `feed_mode` 固定，作者跨过阈值后，旧笔记仍按原方式可见。
- Feed ZSet 的所有 score 为 0，member 为 `yyyyMMddHHmmss:20位补零笔记ID`，以字典序实现 `(create_time,id)` 稳定倒序。`!` 是已加载哨兵，不返回给页面。
- `GET /blog/of/follow` 首次不带参数，后续原样传回 `?cursor=nextCursor`；返回 `list / nextCursor / hasMore`。前端已接入“加载更多”。旧 `lastId/offset` 游标协议不再使用。
- 缓存首次加载、关注变更或标记过期时，按关注作者补齐最近 20 篇普通笔记；大作者按自身索引读取最近 1000 篇拉取笔记。缓存中已有推送记录会保留至容量/TTL 淘汰。Redis 故障时按关注作者和游标查 MySQL，每作者每次最多一页加一条，不扫描全笔记表。按作者查询时每 100 个作者用 `UNION ALL` 合成一条 SQL，不逐个作者查库。
- 旧 `feed:{userId}` 已停用，升级后不会读取它；确认切换完成后可另行清理旧键。

### 启动时预热什么

1. 检查店铺布隆过滤器，缺失或容量不足才按主键分批重建；仍保留每日低峰重建。
2. 按店铺主键每批 500 条登记缓存/GEO同步任务，后台读取当前行执行；首次就绪不代表所有 GEO 已写完。
3. 秒杀券和订单按主键分批读取；库存 `SETNX`，不覆盖运行中的 Redis 预扣值；同步活动时间和已购集合。该流程不新增订单一致性方案。
4. **不再启动时全量遍历关注关系并重复查询所有笔记**。关注和 Feed 按用户/作者访问时加载；后台自动续跑数据库中的待投递、待删缓存、待同步关注任务。不会清空点赞数据。

参数集中在 `application.yaml` 的 `hmdp.consistency`；默认每秒调度，失败延后 5 秒，调度线程池为 4。重试时间用 MySQL `NOW(3)` 计算，避免应用与数据库时区不同导致任务迟迟不执行。

### 接口调整

| 接口 | 调整 |
|---|---|
| `POST /shop` / `PUT /shop` | 缓存补偿、延迟双删、GEO 类型/坐标同步 |
| `POST /blog` | 内容和投递任务一起保存，后台推拉投递 |
| `GET /blog/of/follow` | 合并推拉结果，改用字符串 cursor |
| `POST /voucher-order/seckill/{id}` | 只代表抢到资格，订单号按字符串返回（超出 JS 安全整数范围） |
| `GET /voucher-order/seckill/{id}/result` | 新增：`SUCCESS`（带 `orderId`）/ `PROCESSING` / `NONE`，以 MySQL 订单为准；前端抢到后轮询 |
| `POST /voucher/seckill` | Redis 库存和活动时间在事务提交前写入，写失败则整体回滚 |
| `PUT /blog/like/{id}?liked=true` | 显式目标状态，返回 `liked / isLike` |
| `PUT /blog-comments/like/{id}?liked=false` | 同上，false 表示取消 |
| `PUT /follow/{id}/{isFollow}` | 关系、版本、补偿标记同事务；重复请求保持目标状态 |
| `GET /follow/common/{id}` | 版本校验、按用户恢复、MySQL 降级 |
| `DELETE /blog-comments/{id}` | 标记删除并可靠清理 Redis 点赞 |
