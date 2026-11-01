# 好味杭州（hm-dianping）接口文档

> 本文档按当前代码整理（2026-10-23），列出项目全部 36 个接口，说明每个接口的作用和实现方法，用于学习这个项目。
>
> 表结构和 Redis Key 的完整清单见项目根目录的 `README.md`。

## 目录

- **0. 推荐学习顺序**
- **1. 通用约定**：访问地址、返回格式、登录与鉴权、HTTP 状态码
- **2. 接口总览**：36 个接口一览
- **3. 接口详情**
  - 3.1 用户（8 个）
  - 3.2 商户（6 个）
  - 3.3 优惠券与秒杀（5 个）
  - 3.4 探店笔记（8 个）
  - 3.5 评论（4 个）
  - 3.6 关注（3 个）
  - 3.7 上传（2 个）
- **附录 A**：后台定时任务与启动预热
- **附录 B**：Lua 脚本一览

## 每个接口怎么读

每个接口按同样的顺序介绍，简单的接口会省略部分小节：

1. **作用**：一句话说明接口做什么
2. **基本信息**：是否需要登录、限流、代码位置（`类名.方法名`）
3. **请求参数** 与 **返回示例**
4. **实现流程**：按代码执行顺序编号
5. **设计要点**：为什么这样实现，是学习的重点

每个模块开头有一段 **模块概览**，介绍这个模块用到的表、Redis 数据和核心知识点；几个接口共用的步骤也放在模块概览里，只写一次。

---

## 0. 推荐学习顺序

从简单到复杂，每一步都建立在前一步的基础上：

| 顺序 | 接口 | 能学到什么 |
|---|---|---|
| 1 | 3.1.1 ～ 3.1.4 验证码、登录 | Redis 存验证码和登录态、拦截器 |
| 2 | 3.2.1 商户详情 | 缓存穿透、缓存击穿、布隆过滤器、互斥锁 |
| 3 | 3.2.3 修改商户 | 先改库再删缓存、延迟双删、失败补偿 |
| 4 | 3.2.4 按分类查询商户 | Redis GEO 附近查询 |
| 5 | 3.1.7 ～ 3.1.8 签到 | Redis Bitmap |
| 6 | 3.3.1 店铺优惠券列表 | 多级缓存（Caffeine + Redis） |
| 7 | 3.3.4 ～ 3.3.5 秒杀下单 | Lua 原子操作、Kafka 削峰、幂等、一人一单 |
| 8 | 3.4.7 笔记点赞 | Redis ZSet、幂等请求、计数异步写回 MySQL |
| 9 | 3.5.1 评论列表 | 两级评论、批量查询避免 N+1 |
| 10 | 3.6.1 关注 | 用版本号判断缓存是否过期 |
| 11 | 3.4.6、3.4.5 发布笔记、关注流 | Feed 推拉结合、游标分页 |

---

## 1. 通用约定

### 1.1 访问地址

| 场景 | 地址 |
|---|---|
| 直接访问后端 | `http://127.0.0.1:8081` |
| 通过前端开发服务器 | `http://127.0.0.1:5174/api`（Vite 去掉 `/api` 前缀后转发到 8081） |

文档中的路径都不带 `/api` 前缀。

### 1.2 统一返回格式

所有接口都返回 `Result` 对象：

```json
{ "success": true, "errorMsg": "失败原因", "data": {}, "total": 100 }
```

| 字段 | 说明 |
|---|---|
| `success` | 业务是否成功 |
| `errorMsg` | 失败原因，只在 `success=false` 时出现 |
| `data` | 返回的数据 |
| `total` | 总条数，只有评论列表返回 |

- 值为 `null` 的字段不会输出（配置项 `jackson.default-property-inclusion: non_null`），所以成功时看不到 `errorMsg`，没有数据的接口只返回 `{"success": true}`。
- **业务失败（如"库存不足"）的 HTTP 状态码仍然是 200**，要看 `success` 判断结果。
- 时间字段格式为 `2026-10-23T10:15:00`。金额单位是**分**。

### 1.3 登录与鉴权

登录后拿到 token，之后每个请求都带上请求头：

```http
authorization: <token>
```

注意没有 `Bearer` 前缀。后端用两个拦截器处理（注册在 `MvcConfig`）：

1. **`RefreshTokenInterceptor`**（拦截所有请求）：请求头里有 token 时，从 Redis `login:token:{token}` 读出用户放进 ThreadLocal（`UserHolder`），并把 token 有效期续到 36000 分钟；请求结束后清理 ThreadLocal。没有 token 不拦截。
2. **`LoginInterceptor`**：ThreadLocal 里没有用户、又不是公开接口时，返回 HTTP 401。

为什么拆成两个：刷新有效期对所有请求都要做（包括公开接口），而"必须登录"只针对部分接口。

**公开接口**（不登录也能调用）：

- `/user/code`、`/user/login`、`/blog/hot`、`/blog-comments/list`、`/imgs/**`
- `/shop/**`、`/voucher/**`、`/shop-type/**` 下的 **GET** 请求（同一路径下的新增、修改仍需登录）

### 1.4 HTTP 状态码

| 状态码 | 含义 |
|---|---|
| 200 | 请求已处理，成功与否看 `success` |
| 400 | 缺少必填参数，或参数类型不对（由 Spring 直接返回，格式不是 Result） |
| 401 | 未登录，或 token 已失效 |
| 429 | 被限流（`@RateLimiter`），`errorMsg` 为提示语 |

代码里没有预料到的异常，由 `WebExceptionAdvice` 统一返回 `success=false`、`errorMsg="服务器异常，请稍后重试"`，详细信息只写到后端日志。

---

## 2. 接口总览

共 36 个接口。"登录"一列：✅ 需要登录，— 不需要。编号对应第 3 节的详细说明。

### 用户 `/user`

| 编号 | 方法 | 路径 | 登录 | 作用 |
|---|---|---|---|---|
| 3.1.1 | POST | `/user/code` | — | 发送手机验证码（演示环境直接返回验证码） |
| 3.1.2 | POST | `/user/login` | — | 手机号 + 验证码登录，新手机号自动注册 |
| 3.1.3 | POST | `/user/logout` | ✅ | 退出登录，删除 token |
| 3.1.4 | GET | `/user/me` | ✅ | 当前登录的用户 |
| 3.1.5 | GET | `/user/{id}` | ✅ | 用户基本信息（昵称、头像） |
| 3.1.6 | GET | `/user/info/{id}` | ✅ | 用户详细资料（城市、简介等） |
| 3.1.7 | POST | `/user/sign` | ✅ | 今日签到 |
| 3.1.8 | GET | `/user/sign/count` | ✅ | 截至今天的连续签到天数 |

### 商户 `/shop`、`/shop-type`

| 编号 | 方法 | 路径 | 登录 | 作用 |
|---|---|---|---|---|
| 3.2.1 | GET | `/shop/{id}` | — | 商户详情（布隆过滤器 + Redis 缓存） |
| 3.2.2 | POST | `/shop` | ✅ | 新增商户 |
| 3.2.3 | PUT | `/shop` | ✅ | 修改商户 |
| 3.2.4 | GET | `/shop/of/type` | — | 按分类分页查询；传坐标时按距离排序 |
| 3.2.5 | GET | `/shop/of/name` | — | 按名称关键字分页查询 |
| 3.2.6 | GET | `/shop-type/list` | — | 商户分类列表 |

### 优惠券与秒杀 `/voucher`、`/voucher-order`

| 编号 | 方法 | 路径 | 登录 | 作用 |
|---|---|---|---|---|
| 3.3.1 | GET | `/voucher/list/{shopId}` | — | 店铺秒杀券列表（多级缓存 + 实时库存） |
| 3.3.2 | POST | `/voucher` | ✅ | 新增普通券 |
| 3.3.3 | POST | `/voucher/seckill` | ✅ | 新增秒杀券 |
| 3.3.4 | POST | `/voucher-order/seckill/{id}` | ✅ | 秒杀下单（Redis 预扣库存 + Kafka 异步落库） |
| 3.3.5 | GET | `/voucher-order/seckill/{id}/result` | ✅ | 查询秒杀下单结果 |

### 探店笔记 `/blog`

| 编号 | 方法 | 路径 | 登录 | 作用 |
|---|---|---|---|---|
| 3.4.1 | GET | `/blog/hot` | — | 热门笔记（按点赞数排序） |
| 3.4.2 | GET | `/blog/{id}` | ✅ | 笔记详情 |
| 3.4.3 | GET | `/blog/of/me` | ✅ | 我发布的笔记 |
| 3.4.4 | GET | `/blog/of/user` | ✅ | 指定用户发布的笔记 |
| 3.4.5 | GET | `/blog/of/follow` | ✅ | 关注流（推拉结合，游标分页） |
| 3.4.6 | POST | `/blog` | ✅ | 发布笔记 |
| 3.4.7 | PUT | `/blog/like/{id}` | ✅ | 点赞 / 取消点赞 |
| 3.4.8 | GET | `/blog/likes/{id}` | ✅ | 最早点赞的 5 个用户 |

### 评论 `/blog-comments`

| 编号 | 方法 | 路径 | 登录 | 作用 |
|---|---|---|---|---|
| 3.5.1 | GET | `/blog-comments/list` | — | 评论列表（一级评论分页，附带回复） |
| 3.5.2 | POST | `/blog-comments` | ✅ | 发表评论或回复 |
| 3.5.3 | PUT | `/blog-comments/like/{id}` | ✅ | 评论点赞 / 取消点赞 |
| 3.5.4 | DELETE | `/blog-comments/{id}` | ✅ | 删除自己的评论（连同下面的回复） |

### 关注 `/follow`

| 编号 | 方法 | 路径 | 登录 | 作用 |
|---|---|---|---|---|
| 3.6.1 | PUT | `/follow/{id}/{isFollow}` | ✅ | 关注 / 取消关注 |
| 3.6.2 | GET | `/follow/or/not/{id}` | ✅ | 是否已关注某人 |
| 3.6.3 | GET | `/follow/common/{id}` | ✅ | 我和某人的共同关注 |

### 上传 `/upload`

| 编号 | 方法 | 路径 | 登录 | 作用 |
|---|---|---|---|---|
| 3.7.1 | POST | `/upload/blog` | ✅ | 上传笔记图片 |
| 3.7.2 | GET | `/upload/blog/delete` | ✅ | 删除笔记图片 |

---

## 3. 接口详情

按模块介绍，每个模块先看"模块概览"，再看具体接口。

## 3.1 用户

### 模块概览

| 项目 | 内容 |
|---|---|
| 数据表 | `tb_user`（手机号、昵称、头像）、`tb_user_info`（城市、简介等扩展资料） |
| Redis | `login:code:*` 验证码、`login:token:*` 登录态、`sign:*` 签到位图 |
| 核心知识点 | 用 Redis 代替 Session 保存登录态；用 Bitmap 记录签到 |
| 代码 | `UserController`、`UserServiceImpl` |

---

### 3.1.1 `POST /user/code` 发送验证码

**作用**：给手机号生成 6 位验证码。演示环境不发短信，直接在返回值里给出验证码。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 代码位置 | `UserController.sendCode` → `UserServiceImpl.sendCode` |

**请求参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `phone` | query | string | 是 | 手机号 |

**请求示例**：`POST /user/code?phone=13686869696`

**返回示例**

```json
{ "success": true, "data": "197315" }
```

| errorMsg | 原因 |
|---|---|
| 手机号格式错误！ | 手机号不符合 `RegexPatterns.PHONE_REGEX` |
| 验证码发送过于频繁，请稍后再试 | 同一手机号 60 秒内重复发送 |

**实现流程**

1. 校验手机号格式。
2. 执行 `SET login:code:limit:{phone} 1 NX EX 60`：写入成功才继续；已经存在说明 60 秒内发过，直接拒绝。
3. 生成 6 位随机数字。
4. 写入 `login:code:{phone}`（2 分钟过期），并删除输错次数 `login:code:fail:{phone}`。
5. 打印日志，返回验证码。

**设计要点**

- **限流用一条原子命令**：`SET ... NX EX` 同时完成"判断是否存在"和"设置过期时间"，并发请求只有一个能成功。分开写成"先查再写"会有并发漏洞。
- **演示用的捷径**：生产环境不能把验证码返回给前端，否则任何人都能登录任意手机号。

---

### 3.1.2 `POST /user/login` 登录

**作用**：用手机号和验证码登录。手机号第一次出现时自动注册。返回 token。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 请求格式 | JSON |
| 代码位置 | `UserController.login` → `UserServiceImpl.login` |

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `phone` | string | 是 | 手机号 |
| `code` | string | 是 | 6 位验证码，先调用 3.1.1 获取 |
| `password` | string | 否 | 预留字段，目前没有使用 |

**请求示例**

```http
POST /user/login
Content-Type: application/json

{ "phone": "13686869696", "code": "197315" }
```

**返回示例**

成功时 `data` 就是 token：

```json
{ "success": true, "data": "4f3c2a9e1b7d4c0a8e6f5b2d9c1a7e3f" }
```

| errorMsg | 原因 |
|---|---|
| 手机号格式错误！ | `phone` 格式不对 |
| 验证码错误 | 验证码不对，或已经过期 |
| 验证码错误次数过多，请重新获取 | 同一个验证码输错第 5 次，验证码作废 |

**实现流程**

1. 校验手机号格式。
2. 从 Redis 读取 `login:code:{phone}`，和请求里的 `code` 比较。
3. 不一致：输错次数 `login:code:fail:{phone}` 加 1（第一次写入时设 2 分钟过期）。达到 5 次就删除验证码，用户必须重新获取。
4. 一致：删除验证码和输错次数，保证验证码只能用一次。
5. 按手机号查询 `tb_user`；查不到就新建用户，昵称为 `user_` 加 10 位随机字符。
6. 生成 token（去掉横线的 UUID），把用户的 `id`、`nickName`、`icon` 以 Hash 形式写入 `login:token:{token}`，有效期 36000 分钟。
7. 返回 token。

**用到的 Redis 数据**

| Key | 类型 | 过期时间 | 用途 |
|---|---|---|---|
| `login:code:{phone}` | String | 2 分钟 | 验证码 |
| `login:code:limit:{phone}` | String | 60 秒 | 发送间隔限制 |
| `login:code:fail:{phone}` | String | 2 分钟 | 验证码输错次数 |
| `login:token:{token}` | Hash | 36000 分钟，每次请求续期 | 登录态 |

**设计要点**

- **用 Redis 保存登录态，而不是 Session**：多台后端可以共享登录态，前端只需要带 token。
- **用 Hash 存用户**：每个字段可以单独读写，比整个对象存成 JSON 更灵活。
- **只存必要字段**：Hash 里只放 `UserDTO`（id、昵称、头像），不放手机号、密码。
- **限制输错次数**：6 位验证码只有 100 万种组合，不限制次数就能被暴力枚举。

---

### 3.1.3 `POST /user/logout` 退出登录

**作用**：删除 Redis 里的登录态，token 立即失效。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `UserController.logout` |

**请求参数**：请求头 `authorization`。

**返回示例**：`{ "success": true }`

**实现流程**

1. 从请求头读取 token。
2. 删除 `login:token:{token}`。

**注意**：这个接口需要登录，token 已经过期时会返回 401。前端无论成功与否都会清掉本地保存的 token。

---

### 3.1.4 `GET /user/me` 当前登录用户

**作用**：返回当前登录的用户。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `UserController.me` |

**返回示例**

```json
{ "success": true, "data": { "id": 1, "nickName": "小鱼同学", "icon": "/imgs/blogs/blog1.jpg" } }
```

**实现流程**：直接返回 `UserHolder` 里的 `UserDTO`。这个对象是拦截器从 Redis 登录态里读出来的，不查数据库。

**注意**：数据是登录时写入 Redis 的快照，之后数据库里的昵称、头像变了，要重新登录才会更新。

---

### 3.1.5 `GET /user/{id}` 用户基本信息

**作用**：按 id 查询用户的昵称和头像，用于展示其他用户。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `UserController.queryUserById` |

**请求参数**：路径参数 `id`（用户 id）。

**返回示例**：同 3.1.4。用户不存在时返回 `{ "success": true }`，没有 `data`。

**实现流程**

1. 按 id 查询 `tb_user`。
2. 复制成 `UserDTO` 返回，去掉手机号、密码等字段。

---

### 3.1.6 `GET /user/info/{id}` 用户详细资料

**作用**：查询 `tb_user_info` 里的扩展资料。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `UserController.info` |

**请求参数**：路径参数 `id`（用户 id）。

**返回示例**

```json
{
  "success": true,
  "data": { "userId": 1, "city": "杭州", "introduce": "爱吃茶餐厅", "fans": 3, "followee": 2, "credits": 0 }
}
```

| 字段 | 说明 |
|---|---|
| `fans` / `followee` | 粉丝数 / 关注数 |
| `credits` | 积分 |
| `gender`、`birthday`、`level` | 性别、生日、会员级别 |

**实现流程**

1. 按 id 查询 `tb_user_info`。
2. 查不到返回 `{ "success": true }`（还没填写过资料）。
3. 把 `createTime`、`updateTime` 置空（不输出），返回。

**注意**：`fans`、`followee` 是表里存的数字，关注接口（3.6.1）不会更新它们。

---

### 3.1.7 `POST /user/sign` 签到

**作用**：记录当前用户今天已签到。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `UserController.sign` → `UserServiceImpl.sign` |

**返回示例**：`{ "success": true }`

**实现流程**

1. 拼出本月的 key：`sign:{userId}:{yyyyMM}`，例如 `sign:1:202610`。
2. 执行 `SETBIT key (今天是几号 - 1) 1`：23 号签到就把第 22 位设为 1。

**设计要点**

- **Bitmap 很省空间**：每天只占 1 位，一个月最多 31 位（4 个字节）。如果每天存一行记录，一年就是 365 行。
- **天然幂等**：重复签到只是把同一位再设成 1，结果不变。

---

### 3.1.8 `GET /user/sign/count` 连续签到天数

**作用**：统计截至今天连续签到了多少天。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `UserController.signCount` → `UserServiceImpl.signCount` |

**返回示例**：`{ "success": true, "data": 3 }`

**实现流程**

1. 执行 `BITFIELD sign:{userId}:{yyyyMM} GET u{今天是几号} 0`：一次取出本月 1 号到今天的所有位，得到一个无符号整数，**最低位是今天**。
2. 从最低位开始逐位检查：是 1 就计数加 1，再右移一位；遇到 0 就停止。

**举例**：今天是 5 号，本月签了 1、3、4、5 号。

```
日期：     1  2  3  4  5
位：       1  0  1  1  1      → 整数的二进制为 10111
从低位数：5 号 1、4 号 1、3 号 1、2 号 0 → 停止，连续 3 天
```

**注意**：今天还没签到时结果是 0，即使昨天之前一直连续签到。

---

## 3.2 商户

### 模块概览

| 项目 | 内容 |
|---|---|
| 数据表 | `tb_shop`（商户）、`tb_shop_type`（分类） |
| Redis | `cache:shop:{id}` 详情缓存、`lock:shop:{id}` 重建锁、`shop:geo:{typeId}` 按分类的坐标、`shop:geo:types` 商户上次所在分类、`bloom:shop:*` 布隆过滤器 |
| 核心知识点 | 缓存穿透与击穿、先改库再删缓存、延迟双删、Redis GEO |
| 代码 | `ShopController`、`ShopServiceImpl`、`CacheClient`、`ShopBloomFilter`、`ShopCacheSyncService` |

**公共步骤：商户缓存同步（新增、修改商户共用）**

新增或修改商户时，后端在同一个事务里把这行的 `cache_sync_stage` 设为 1（待同步），事务提交后由 `ShopCacheSyncService.trySync` 执行同步：

1. 锁住商户行，读取最新数据（不使用请求里传来的数据，避免用旧值覆盖）。
2. 执行 `shop_sync.lua`，原子完成：
   - 删除详情缓存 `cache:shop:{id}`；
   - 把商户从旧分类的 GEO 集合中移除，写入新分类的 `shop:geo:{typeId}`；
   - 在 `shop:geo:types` 中记录这个商户现在所在的分类。
3. 再把 id 加入一次布隆过滤器。
4. 推进阶段：`1 → 2`（约 1 秒后再删一次缓存），`2 → 0`（完成）。

失败时阶段保持不变，5 秒后由定时任务重试（见附录 A）。

---

### 3.2.1 `GET /shop/{id}` 商户详情

**作用**：按 id 查询商户详情。请求依次经过布隆过滤器、Redis 缓存，都没命中才查 MySQL。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 代码位置 | `ShopController.queryShopById` → `ShopServiceImpl.queryById` → `CacheClient.queryWithMutex` |

**请求参数**：路径参数 `id`（商户 id）。

**返回示例**

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "103茶餐厅",
    "typeId": 1,
    "images": "https://qcloud.dpfile.com/pc/a.jpg,https://qcloud.dpfile.com/pc/b.jpg",
    "area": "大关",
    "address": "金华路锦昌文华苑29号",
    "x": 120.149192,
    "y": 30.316078,
    "avgPrice": 80,
    "sold": 4215,
    "comments": 3035,
    "score": 37,
    "openHours": "10:00-22:00",
    "createTime": "2021-12-22T18:10:39",
    "updateTime": "2022-01-13T17:32:19"
  }
}
```

| 字段 | 说明 |
|---|---|
| `images` | 多张图片地址，用逗号分隔 |
| `x` / `y` | 经度 / 纬度 |
| `avgPrice` | 人均消费（元） |
| `score` | 评分乘 10 后的整数，37 表示 3.7 分 |

商户不存在时：`{ "success": false, "errorMsg": "店铺不存在！" }`

**实现流程**

1. **布隆过滤器**：判断 id"一定不存在"时直接返回"店铺不存在！"，不访问 Redis 和 MySQL。过滤器还没建好或 Redis 出错时直接放行。
2. **读缓存** `cache:shop:{id}`：
   - 命中商户 JSON：直接返回。
   - 命中空字符串：说明之前查过、数据库里没有，返回"店铺不存在！"。
3. **未命中时抢锁** `lock:shop:{id}`（`SET NX`，10 秒过期，值为随机 UUID）：
   - 没抢到：等 50 毫秒回到第 2 步，最多重试 20 次（约 1 秒）。仍未重建完成就报错，不让请求直接打到数据库。
   - 抢到：再查一次缓存（等锁期间可能已经有人重建好），仍然没有才查 MySQL。
4. **写回缓存**：查到数据缓存 30 分钟；没查到缓存空字符串 2 分钟。
5. **释放锁**：用 `unlock.lua` 先比较锁的值再删除，只释放自己加的锁。
6. **Redis 不可用时**（抛出 `DataAccessException`）：跳过缓存，直接查 MySQL。

**针对的缓存问题**

| 问题 | 现象 | 本接口的措施 |
|---|---|---|
| 缓存穿透 | 查询不存在的 id，每次都打到数据库 | 布隆过滤器 + 缓存空值 |
| 缓存击穿 | 热点 key 过期的瞬间，大量请求同时查库 | 互斥锁，只让一个线程查库重建 |

**设计要点**

- **锁的值用随机 UUID，释放前先比较**：如果查库太慢、锁已经过期被别人拿到，直接 `DEL` 会删掉别人的锁。比较和删除要放在一个 Lua 脚本里，保证原子。
- **拿到锁后再查一次缓存**（双重检查）：等锁的线程拿到锁时，缓存可能已经被前一个线程重建好了。
- **布隆过滤器不能删除元素**：已删除的商户仍会被判为"可能存在"，再由空值缓存挡住。每天凌晨 4 点全量重建一次。
- **对比学习**：`CacheClient` 里还保留了"只防穿透"（`queryWithPassThrough`）和"逻辑过期"（`queryWithLogicalExpire`）两种写法，`queryById` 里对应的调用处于注释状态，可以切换对比。

---

### 3.2.2 `POST /shop` 新增商户

**作用**：新增一家商户，返回新商户的 id。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 请求格式 | JSON |
| 代码位置 | `ShopController.saveShop` → `ShopServiceImpl.saveShop` |

**请求参数**（JSON，字段含义同 3.2.1 的返回示例；传了 `id` 也会被忽略）

```json
{
  "name": "运河边深夜食堂", "typeId": 1, "images": "https://example.com/a.jpg",
  "area": "拱宸桥", "address": "桥弄街 12 号", "x": 120.14, "y": 30.32,
  "avgPrice": 60, "sold": 0, "comments": 0, "score": 45, "openHours": "17:00-02:00"
}
```

**返回示例**：`{ "success": true, "data": 23 }`

| errorMsg | 原因 |
|---|---|
| 店铺坐标超出有效范围 | 经度绝对值超过 180，或纬度绝对值超过 85.05112878（Redis GEO 支持的范围） |

**实现流程**（在一个事务里）

1. 校验坐标。
2. 插入 `tb_shop`，拿到自增 id。
3. 把新 id 加入布隆过滤器（**事务提交前**）。
4. 把这行标记为待同步（`cache_sync_stage = 1`）。
5. 事务提交后执行一次"商户缓存同步"（见模块概览）：删除缓存、写入 GEO。

**设计要点**

- **为什么在提交前加布隆过滤器**：如果提交后才加，提交和加入之间的查询会把真实存在的商户判成"不存在"。提交前加，就算事务回滚，也只是过滤器里多了一个不存在的 id，由空值缓存兜底。
- **新增也要删缓存**：这个 id 之前可能被查过，缓存了空值，不删的话 2 分钟内都查不到新商户。
- **为什么把"待同步"写进数据库**：它和商户数据在同一个事务里提交，进程崩溃重启后定时任务会接着做，不会漏掉。

---

### 3.2.3 `PUT /shop` 修改商户

**作用**：修改商户信息。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 请求格式 | JSON |
| 代码位置 | `ShopController.updateShop` → `ShopServiceImpl.update` |

**请求参数**：JSON，字段同 3.2.2，`id` 必填。只更新传了值的字段（MyBatis-Plus 的 `updateById` 会跳过为 null 的字段）。

**返回示例**：`{ "success": true }`

| errorMsg | 原因 |
|---|---|
| 店铺id不能为空 | 没传 `id` |
| 店铺坐标超出有效范围 | 同 3.2.2 |
| 店铺不存在 | id 对应的商户不存在 |

**实现流程**（在一个事务里）

1. 校验 id 和坐标。
2. `SELECT ... FOR UPDATE` 锁住商户行；不存在则返回。
3. 更新 `tb_shop`。
4. 标记待同步（`cache_sync_stage = 1`）。
5. 事务提交后执行"商户缓存同步"：立即删除缓存、移动 GEO；约 1 秒后定时任务再删一次缓存。

**设计要点：先改库，再删缓存，并且删两次（延迟双删）**

- **为什么删缓存而不是更新缓存**：更新缓存要处理"两个写请求谁先写缓存"的顺序问题；删除后由下一次查询重建，简单可靠。
- **为什么提交后才删**：提交前删的话，其他请求在提交前读到旧数据，又会把旧数据写回缓存。
- **为什么还要再删一次**：可能有请求在提交前就读到了旧数据，在第一次删除之后才写回缓存。过 1 秒再删一次，清掉这种旧值。双删能降低不一致的概率，但不是强一致。
- **GEO 也要同步**：分类改了要从旧分类的 GEO 集合里删掉，否则按距离查询时两个分类都能查到它。

---

### 3.2.4 `GET /shop/of/type` 按分类查询商户（可按距离排序）

**作用**：按分类分页查询商户。传了用户坐标时，返回 5 公里内的商户并按距离由近到远排序。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 限流 | 同一 IP 5 秒内最多 10 次，超出返回 429"查询次数过多，请稍后再试" |
| 每页条数 | 5 |
| 代码位置 | `ShopController.queryShopByType` → `ShopServiceImpl.queryShopByType` |

**请求参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `typeId` | query | int | 是 | 分类 id |
| `current` | query | int | 否 | 页码，默认 1 |
| `x` / `y` | query | double | 否 | 用户的经度 / 纬度，两个都传才按距离排序 |

**请求示例**：`GET /shop/of/type?typeId=1&current=1&x=120.149&y=30.316`

**返回示例**：商户数组，传坐标时每个商户多一个 `distance` 字段（单位：米）。

```json
{ "success": true, "data": [ { "id": 1, "name": "103茶餐厅", "distance": 52.3, "...": "其余字段同 3.2.1" } ] }
```

**实现流程**

不传坐标时：MySQL 按 `type_id` 分页查询，直接返回。

传坐标时：

1. 执行 `GEOSEARCH shop:geo:{typeId}`：以 (x, y) 为圆心、半径 5000 米，按距离升序，带上距离，取前 `current × 5` 条。
2. 跳过前 `(current - 1) × 5` 条，得到本页的商户 id 和距离。
3. MySQL 查询 `WHERE id IN (...) ORDER BY FIELD(id, ...)`，保持和距离一致的顺序。
4. 把距离填到每个商户的 `distance` 字段。

**设计要点**

- **GEO 分页的局限**：`GEOSEARCH` 不能"从第 N 条开始取"，只能先取前 `current × 5` 条，再在内存里跳过前面的。页码越大取得越多，适合浅分页。
- **`IN` 不保证顺序**：所以要加 `ORDER BY FIELD(id, ...)`，否则返回顺序和距离顺序不一致。
- **GEO 按分类分开存**：每个分类一个 key，查询时只在这个分类里找。
- **限流按 IP**：如果按方法限流，全站所有用户共用一个计数器，几个人同时浏览就会被拦住。

---

### 3.2.5 `GET /shop/of/name` 按名称搜索商户

**作用**：按名称关键字模糊查询商户。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 每页条数 | 10 |
| 代码位置 | `ShopController.queryShopByName`（直接在 Controller 里查询） |

**请求参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `name` | query | string | 否 | 关键字；不传时返回全部商户 |
| `current` | query | int | 否 | 页码，默认 1 |

**返回示例**：商户数组，字段同 3.2.1。

**实现流程**：MySQL `WHERE name LIKE '%关键字%'` 分页查询，没有缓存。

**注意**：前后都带 `%` 的模糊查询用不上索引，数据量大时要换成 Elasticsearch 等搜索引擎。

---

### 3.2.6 `GET /shop-type/list` 商户分类列表

**作用**：返回全部商户分类，用于首页的分类标签。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 代码位置 | `ShopTypeController.queryTypeList` |

**返回示例**

```json
{ "success": true, "data": [ { "id": 1, "name": "美食", "icon": "/types/ms.png", "sort": 1 } ] }
```

**实现流程**：MySQL 查询 `tb_shop_type`，按 `sort` 升序返回，没有缓存。

---

## 3.3 优惠券与秒杀

### 模块概览

| 项目 | 内容 |
|---|---|
| 数据表 | `tb_voucher`（券的基本信息）、`tb_seckill_voucher`（秒杀券的库存和时间，与券一对一）、`tb_voucher_order`（订单，唯一索引 `uk_user_voucher(user_id, voucher_id)`） |
| Redis | `seckill:stock:{id}` 库存、`seckill:order:{id}` 已抢到的用户、`seckill:time:{id}` 活动时间、`cache:voucher:list:{shopId}` 券列表缓存 |
| Kafka | topic `seckill-voucher-order`（3 个分区）、死信 topic `seckill-voucher-order.DLT` |
| 核心知识点 | 多级缓存、Lua 原子操作、消息队列削峰、消费幂等、一人一单、防超卖 |
| 代码 | `VoucherController`、`VoucherServiceImpl`、`VoucherListCacheService`、`VoucherOrderController`、`VoucherOrderServiceImpl`、`SeckillVoucherConsumer`、`KafkaConfig` |

**普通券与秒杀券**：两者都存在 `tb_voucher`，`type = 0` 是普通券，`type = 1` 是秒杀券。秒杀券的库存和活动时间另存在 `tb_seckill_voucher`。

---

### 3.3.1 `GET /voucher/list/{shopId}` 店铺秒杀券列表

**作用**：查询某个店铺上架中的秒杀券，库存为实时库存。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 代码位置 | `VoucherController.queryVoucherOfShop` → `VoucherServiceImpl.queryVoucherOfShop` → `VoucherListCacheService.getVoucherByShopId` |

**请求参数**：路径参数 `shopId`（店铺 id）。

**返回示例**

```json
{
  "success": true,
  "data": [
    {
      "id": 1002, "shopId": 1, "title": "50 元代金券", "subTitle": "周一至周日可用",
      "rules": "全场通用，无需预约", "payValue": 1990, "actualValue": 5000, "type": 1,
      "stock": 48, "beginTime": "2026-10-01T00:00:00", "endTime": "2026-12-31T23:59:59"
    }
  ]
}
```

`payValue` 是售价、`actualValue` 是抵扣金额，单位都是分（上例：19.9 元买 50 元券）。

**注意**：查询 SQL 只返回 `type = 1`（秒杀券）且 `status = 1`（上架）的券，普通券不会出现在这个列表里。

**实现流程**

1. **按缓存模式取列表**：配置项 `hmdp.cache.voucher-list.mode`，当前为 `caffeine`。

   | 模式 | 查找顺序 |
   |---|---|
   | `mysql` | 直接查 MySQL |
   | `redis` | Redis → MySQL |
   | `caffeine`（当前） | Caffeine 本地缓存 → Redis → MySQL |

   - **L1 Caffeine**（每台后端自己的内存）：写入 5 秒后再被访问时，先返回旧值并在后台刷新；10 分钟后强制过期；最多缓存 1000 个店铺。
   - **L2 Redis** `cache:voucher:list:{shopId}`：30 分钟过期；店铺没有券时缓存空列表 2 分钟。
   - **MySQL**：`tb_voucher` 左连接 `tb_seckill_voucher`，查完写回 Redis。
2. **覆盖实时库存**：先复制一份列表，再用 `MGET seckill:stock:{id} ...` 一次读出所有券的 Redis 库存，覆盖 `stock`（负数按 0 显示）。

**设计要点**

- **动静分离**：标题、规则这类很少变的数据走多级缓存；库存每秒都在变，单独从 Redis 实时读取。
- **为什么要先复制列表**：Caffeine 里的对象被所有请求共享，直接修改会互相影响。
- **本地缓存的代价**：多台后端时，一台机器删除缓存，其他机器的本地缓存要等最多 5 秒刷新才能看到新数据。

---

### 3.3.2 `POST /voucher` 新增普通券

**作用**：新增一张普通券，返回券 id。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 请求格式 | JSON |
| 代码位置 | `VoucherController.addVoucher` |

**请求参数**

| 字段 | 类型 | 说明 |
|---|---|---|
| `shopId` | long | 店铺 id |
| `title` / `subTitle` / `rules` | string | 标题、副标题、使用规则 |
| `payValue` / `actualValue` | long | 售价、抵扣金额（分） |
| `type` | int | 传 0 |
| `status` | int | 1 上架，2 下架，3 过期 |

**返回示例**：`{ "success": true, "data": 1005 }`

**实现流程**：直接插入 `tb_voucher`，返回自增 id。不清理列表缓存，因为列表（3.3.1）只显示秒杀券。

---

### 3.3.3 `POST /voucher/seckill` 新增秒杀券

**作用**：新增一张秒杀券，同时写入库存和活动时间，创建后马上可以抢购。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 请求格式 | JSON |
| 代码位置 | `VoucherController.addSeckillVoucher` → `VoucherServiceImpl.addSeckillVoucher` |

**请求参数**：3.3.2 的全部字段，再加：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | int | **必须传 1**，后端不会自动设置；传 0 的话这张券不会出现在列表里 |
| `stock` | int | 库存 |
| `beginTime` / `endTime` | string | 活动开始、结束时间，格式 `2026-10-23T10:00:00` |

**返回示例**：`{ "success": true, "data": 1006 }`

**实现流程**（在一个事务里）

1. 插入 `tb_voucher`，拿到券 id。
2. 插入 `tb_seckill_voucher`（`voucher_id`、`stock`、`begin_time`、`end_time`）。
3. **事务提交前写 Redis**：`SET seckill:stock:{id} 库存`；`HSET seckill:time:{id}`，`begin`、`end` 字段为毫秒时间戳。写失败会抛异常，整个事务回滚。
4. 事务提交后清理这个店铺的券列表缓存（Redis 和本地缓存）。清理失败只记日志。

**设计要点**

- **为什么 Redis 要在提交前写**：秒杀脚本只读 Redis。如果提交后才写，而写 Redis 失败了，就会出现"券建好了，抢购却一直提示活动不存在"；接口还会报错，用户重试又建出一张重复的券。提交前写，失败时数据库一起回滚，接口如实报错。
- **反过来的情况**：Redis 写成功但事务提交失败时，Redis 里会留下这张券的 key。这张券不在列表里；就算有人按 id 抢，消费者扣 MySQL 库存时也会失败，订单回滚并退回资格。
- **为什么列表缓存要提交后清理**：提交前清理的话，其他请求会在提交前把旧列表重新写回缓存。

---

### 3.3.4 `POST /voucher-order/seckill/{id}` 秒杀下单

**作用**：抢购秒杀券。接口只在 Redis 里判断资格并预扣库存，订单通过 Kafka 异步写入 MySQL。**返回成功只代表抢到了资格**，最终结果用 3.3.5 查询。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 限流 | 全局 1 秒 1000 次；同一用户 1 秒 5 次（超出返回 HTTP 429） |
| 代码位置 | 下单：`VoucherOrderController.seckillVoucher` → `VoucherOrderServiceImpl.seckillVoucher`<br>落库：`SeckillVoucherConsumer.processMessage` → `VoucherOrderServiceImpl.createVoucherOrder` |

**请求参数**：路径参数 `id`（秒杀券 id，即 `tb_voucher.id`）。

**返回示例**

成功时 `data` 是订单号。订单号超过了 JavaScript 能精确表示的整数范围，所以按字符串返回：

```json
{ "success": true, "data": "639904398587199490" }
```

| errorMsg | 原因 |
|---|---|
| 库存不足 | Redis 中的库存为 0 |
| 重复下单 | 该用户已经抢到过这张券 |
| 活动不存在 | Redis 中没有这张券的库存数据 |
| 活动尚未开始 / 活动已结束 | 不在活动时间内 |
| 系统繁忙，请稍后再试 | Kafka 消息没有发出去，资格已经退回 |
| 抢购人数过多，请稍后再试 / 操作太频繁，请稍后再试 | 被限流（HTTP 429） |

**整体流程**

```
用户请求
  ├─ 限流（Redis 滑动窗口）
  ├─ seckill.lua：检查时间、库存、一人一单，扣减 Redis 库存     ← 同步，毫秒级
  ├─ 生成订单号，发送 Kafka 消息
  └─ 返回订单号（只代表抢到资格）
           │
           ▼ 异步
  Kafka 消费者：插入订单 + 扣减 MySQL 库存（同一个事务）
           ├─ 成功：订单落库
           └─ 失败：重试 3 次 → 转入死信 topic → 退回资格
```

**实现流程：同步部分（接口内）**

1. **限流**：`@RateLimiter` 切面执行 `limiter.lua`（滑动窗口：ZSet 里每个请求一条记录，先删掉窗口外的，再数窗口内有几条），超限时抛出 `RateLimitException`，返回 429。
2. **本机售罄标记**：这张券在 1 秒内已经在本机返回过"库存不足"时，直接返回，不再访问 Redis。
3. **执行 `seckill.lua`**：按下表顺序检查，全部通过才扣库存。整个脚本在 Redis 中原子执行，不会被其他请求打断。

   | 顺序 | 检查内容 | 不通过时的返回码 |
   |---|---|---|
   | 1 | `seckill:stock:{id}` 是否存在 | 3 活动不存在 |
   | 2 | 当前时间是否在 `seckill:time:{id}` 的开始和结束时间之间 | 4 未开始 / 5 已结束 |
   | 3 | 库存是否大于 0 | 1 库存不足 |
   | 4 | 用户是否已经在 `seckill:order:{id}` 集合中 | 2 重复下单 |
   | — | 全部通过：库存减 1，用户加入集合 | 0 成功 |

4. **生成订单号**：`RedisIdWorker` 生成 64 位 id，高位是距 2022-01-01 的秒数，低 32 位是当天的自增序号（`icr:order:{yyyy:MM:dd}`）。
5. **发送 Kafka 消息**：发到 topic `seckill-voucher-order`，以 userId 作为消息 key。发送失败时执行 `seckill_rollback.lua`：把用户移出集合，加回库存。
6. 返回订单号。

**实现流程：异步部分（Kafka 消费者）**

1. `SeckillVoucherConsumer` 收到消息（消费者组 `hm-dianping-seckill`，3 个消费线程），调用 `createVoucherOrder`（`@Transactional`）。
2. **先插入订单**。主键或唯一索引 `uk_user_voucher(user_id, voucher_id)` 冲突时，说明是重复消息，直接结束。
3. **再扣减 MySQL 库存**：`UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0`。
4. **MySQL 库存不足时**：回滚订单，退回用户资格。这时不加回 Redis 库存，而是把它清零，因为 MySQL 已经卖完了。
5. **其他异常**：每隔 1 秒重试，共 3 次。仍然失败就转发到死信 topic `seckill-voucher-order.DLT`；确认库里没有这笔订单后，退回资格并加回库存。

**设计要点**

- **削峰**：请求只访问 Redis，MySQL 的写入交给消费者按自己的速度处理。
- **为什么用 Lua**：判断库存和扣减库存如果分成两条命令，两个请求可能同时看到"还剩 1 个"，都扣成功，造成超卖。Lua 脚本在 Redis 里一次执行完，中间不会插入别的命令。
- **防超卖**：Redis 侧 Lua 原子完成"判断 + 扣减"；MySQL 侧用 `stock > 0` 的条件更新兜底。
- **一人一单**：Redis Set 拦下绝大多数重复请求；MySQL 唯一索引兜底，即使 Redis 数据丢失也不会产生重复订单。
- **消息重复投递**：先插订单再扣库存，重复消息在插入时就被唯一键拦下，不会多扣库存。
- **以 userId 作为消息 key**：同一用户的消息进入同一个分区、按顺序处理；不同用户分散到各分区并行消费。
- **资格不等于订单**：接口返回成功时订单还没落库，前端需要轮询 3.3.5。

---

### 3.3.5 `GET /voucher-order/seckill/{id}/result` 查询秒杀结果

**作用**：查询当前用户某张秒杀券的下单结果。前端在 3.3.4 返回成功后调用。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `VoucherOrderController.querySeckillResult` → `VoucherOrderServiceImpl.querySeckillResult` |

**请求参数**：路径参数 `id`（秒杀券 id）。

**返回示例**

```json
{ "success": true, "data": { "status": "SUCCESS", "orderId": "639904398587199490" } }
```

| status | 含义 | 判断依据 |
|---|---|---|
| `SUCCESS` | 订单已创建，带 `orderId` | MySQL 中有这个用户这张券的订单 |
| `PROCESSING` | 处理中 | 没有订单，但用户还在 `seckill:order:{id}` 中（消息还在发送或消费） |
| `NONE` | 没有资格 | 两者都没有：没抢过，或下单失败、资格已经退回，可以重新抢 |

**实现流程**

1. 查询 `tb_voucher_order` 中 `user_id`、`voucher_id` 都匹配的订单（走唯一索引）。有就返回 `SUCCESS`。
2. 没有订单时，执行 `SISMEMBER seckill:order:{id} userId`：在集合里返回 `PROCESSING`，否则返回 `NONE`。

**前端用法**：抢到资格后每 0.5 秒查一次，最多查 10 次；拿到 `SUCCESS` 或 `NONE` 就停止。

**设计要点**

- **以 MySQL 订单为准**：Redis 里"有资格"只说明抢到过，订单可能还在路上，也可能失败了。只有 MySQL 里有订单才算成功。
- **不需要额外存状态**：三种状态都能从已有数据（订单表 + 已购集合）推出来。

---

## 3.4 探店笔记

### 模块概览

| 项目 | 内容 |
|---|---|
| 数据表 | `tb_blog`（笔记）、`tb_user`、`tb_follow` |
| Redis | `blog:liked:{id}` 点赞名单、`likes:meta:blog:{id}` 点赞元数据、`likes:dirty` 待同步计数、`feed:inbox:*` / `feed:outbox:*` 收件箱与发件箱 |
| 核心知识点 | 批量查询避免 N+1、点赞幂等与计数异步写回、Feed 推拉结合、游标分页 |
| 代码 | `BlogController`、`BlogServiceImpl`、`LikeConsistencyService`、`HybridFeedService` |

**返回的笔记对象**

```json
{
  "id": 12, "shopId": 1, "userId": 1, "name": "小鱼同学", "icon": "/imgs/blogs/blog1.jpg",
  "title": "运河边的茶餐厅", "images": "/blogs/3/7/xxx.jpg,/blogs/1/2/yyy.jpg",
  "content": "菠萝油很好吃……", "liked": 8, "comments": 2, "isLike": true,
  "createTime": "2026-10-23T10:15:00", "updateTime": "2026-10-23T10:15:00"
}
```

| 字段 | 说明 |
|---|---|
| `name` / `icon` | 作者昵称和头像（不在 `tb_blog` 表里，查询时填充） |
| `images` | 图片路径，逗号分隔，最多 9 张 |
| `liked` | 点赞数 |
| `isLike` | 当前用户是否点过赞（未登录时为 false） |

**公共步骤：填充作者和点赞状态（`fillAuthorAndLiked`）**

笔记列表、详情、关注流都会调用：

1. 收集这一批笔记的作者 id，**一次** `IN` 查询 `tb_user`，填入 `name`、`icon`。
2. 用 Redis **pipeline 一次发出**所有命令：每篇笔记执行 `like_snapshot.lua`，读出当前点赞数和当前用户是否点过赞。
3. Redis 有数据时用 Redis 的点赞数覆盖 `liked`（比 MySQL 更新）；Redis 出错时保留 MySQL 里的数。

这样一页 10 篇笔记只需要 1 次 MySQL 查询 + 1 次 Redis 往返。如果每篇单独查，就是 10 次 MySQL + 10 次 Redis，即 N+1 问题。

---

### 3.4.1 `GET /blog/hot` 热门笔记

**作用**：按点赞数从高到低分页返回笔记，用于社区首页。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 每页条数 | 10 |
| 代码位置 | `BlogController.queryHotBlog` → `BlogServiceImpl.queryHotBlog` |

**请求参数**：`current`（query，页码，默认 1）。

**返回示例**：笔记数组，格式见模块概览。

**实现流程**

1. MySQL 按 `liked` 降序分页查询 `tb_blog`。
2. 执行公共步骤，填充作者和点赞状态。

**注意**：排序用的是 MySQL 里的 `liked`（后台每秒同步一次），显示用的是 Redis 里的实时数，所以刚点完赞时显示的数变了，排序可能还没变。

---

### 3.4.2 `GET /blog/{id}` 笔记详情

**作用**：查询一篇笔记的详情。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `BlogController.queryBlogById` → `BlogServiceImpl.queryBlogById` |

**请求参数**：路径参数 `id`（笔记 id）。

**返回示例**：一个笔记对象。不存在时 `errorMsg` 为"笔记不存在！"。

**实现流程**：按 id 查询 `tb_blog`，执行公共步骤后返回。

---

### 3.4.3 `GET /blog/of/me` 我的笔记

**作用**：分页查询当前用户发布的笔记，最新的在前。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 每页条数 | 10 |
| 代码位置 | `BlogController.queryMyBlog` → `BlogServiceImpl.queryByAuthor` |

**请求参数**：`current`（query，页码，默认 1；小于 1 时返回"页码错误"）。

**实现流程**

1. MySQL 查询 `user_id = 当前用户` 的笔记，按 `create_time`、`id` 降序分页（走索引 `idx_blog_author_time`）。
2. 执行公共步骤后返回。

---

### 3.4.4 `GET /blog/of/user` 指定用户的笔记

**作用**：分页查询某个用户发布的笔记。和 3.4.3 是同一个方法，只是作者 id 由参数传入。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `BlogController.queryBlogByUserId` → `BlogServiceImpl.queryByAuthor` |

**请求参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | query | long | 是 | 作者的用户 id |
| `current` | query | int | 否 | 页码，默认 1 |

---

### 3.4.5 `GET /blog/of/follow` 关注流

**作用**：按时间倒序返回我关注的人发布的笔记，滚动加载。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 每页条数 | 10 |
| 代码位置 | `BlogController.queryBlogOfFollow` → `HybridFeedService.query` |

**请求参数**：`cursor`（query，可选）。第一页不传；之后原样传回上一页返回的 `nextCursor`。

**返回示例**

```json
{
  "success": true,
  "data": {
    "list": [ { "id": 12, "title": "运河边的茶餐厅", "...": "笔记对象" } ],
    "nextCursor": "20261023101500:00000000000000000012",
    "hasMore": true
  }
}
```

`cursor` 格式不对时返回"无效的分页游标"。

#### 先理解几个概念

**推模式和拉模式**

| | 推模式（普通作者） | 拉模式（大作者） |
|---|---|---|
| 条件 | 发布时粉丝少于 10000 | 发布时粉丝达到 10000 |
| 发布时 | 把笔记写进每个粉丝的收件箱 `feed:inbox:{粉丝id}` | 只写进自己的发件箱 `feed:outbox:{作者id}` |
| 读取时 | 直接读自己的收件箱 | 去所关注大作者的发件箱里拉 |
| 优点 | 读得快 | 发布时不用写几万个收件箱 |

每篇笔记在发布时就确定用哪种模式（`tb_blog.feed_mode`），之后不再改变。作者粉丝数跨过阈值后，旧笔记仍按原来的方式可见。

**收件箱里存什么**

收件箱和发件箱都是 ZSet。member 的格式为 `发布时间:20位补零的笔记id`，例如 `20261023101500:00000000000000000012`；所有 score 都是 0。

- score 相同时，ZSet 按 member 的字典序排序，正好等于"先按时间、再按 id"排序。
- 读取用 `ZREVRANGEBYLEX`，从大到小取，就是最新的在前。
- 每个箱子最多保留 1000 条；20 分钟过期，每次写入或重建时重新计时。
- 特殊成员 `!` 是"已加载"标记，表示这个箱子是完整重建过的，不会返回给前端。

**游标分页**

`nextCursor` 是本页最后一条笔记的 member。下一页从比它小的 member 开始取（不包含它本身）。

和"页码 + 每页条数"相比，游标分页在有新笔记插入时不会出现重复或漏掉；和"时间戳 + offset"相比，同一秒发布的多篇笔记也能稳定排序。

#### 实现流程

1. **校验游标格式**：必须是 `14位时间:20位数字`。
2. **取关注列表**：读 `tb_user.follow_version`，再取我关注的作者 id 集合（读法见 3.6 模块概览）。
3. **确保收件箱完整**：`feed:ready:{userId}` 等于当前关注版本、并且收件箱里有 `!` 标记，说明收件箱可以直接用；否则重建：
   1. 删掉收件箱里已删除笔记、已取关作者的笔记；
   2. 为每个关注的作者补上最近 20 篇推模式笔记（每 100 个作者合成一条 `UNION ALL` SQL，见下方设计要点）；
   3. 写入 `!` 标记，记录 `feed:ready:{userId}` = 当前关注版本（10 分钟过期）。
4. **读收件箱**：从游标位置往后取，最多 1000 条。
5. **读大作者的发件箱**：找出关注的人里发过拉模式笔记的作者，确保他们的发件箱已加载（最近 1000 篇），各取 11 条。
6. **合并**：所有候选放进按 member 倒序的有序集合，自动去重。
7. **过滤**：按 100 条一批查 MySQL，去掉已删除的笔记和已取关作者的笔记，凑够 11 条为止。
8. **组装结果**：取够 11 条说明还有下一页，`hasMore = true`，返回前 10 条；`nextCursor` 为第 10 条的 member。
9. 执行公共步骤，填充作者和点赞状态。

**Redis 出错时**：跳过收件箱，直接按关注的作者查 MySQL，每个作者取游标之后的 11 篇，合并后同样处理。

#### 设计要点

- **关注变化时收件箱怎么更新**：关注或取关会让关注版本加 1（见 3.6.1），下次读关注流时版本对不上，收件箱就会重建：新关注的人的最近 20 篇会补进来，取关的人的笔记会被删掉。
- **重建时合并而不是替换**：重建期间可能正好有新笔记推送进来，直接替换会把它丢掉。
- **批量查询**：补收件箱时如果逐个作者查库，关注几百人就要几百次数据库往返。MySQL 5.7 没有窗口函数，所以把每个作者的子查询用 `UNION ALL` 拼成一条 SQL，每个子查询仍然走索引、只取几条：

  ```sql
  (SELECT id, user_id, ... FROM tb_blog WHERE user_id = 1 AND feed_mode = 0
     ORDER BY create_time DESC, id DESC LIMIT 20)
  UNION ALL
  (SELECT id, user_id, ... FROM tb_blog WHERE user_id = 2 AND feed_mode = 0
     ORDER BY create_time DESC, id DESC LIMIT 20)
  ```

---

### 3.4.6 `POST /blog` 发布笔记

**作用**：发布一篇探店笔记，然后在后台推送给粉丝。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 请求格式 | JSON |
| 代码位置 | `BlogController.saveBlog` → `BlogServiceImpl.saveBlog` → `HybridFeedService.register` |

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `shopId` | long | 是 | 关联的店铺 |
| `title` | string | 是 | 标题 |
| `content` | string | 是 | 正文 |
| `images` | string | 否 | 图片路径（3.7.1 的返回值），逗号分隔 |

**返回示例**：`{ "success": true, "data": 12 }`（笔记 id）。缺少必填项时 `errorMsg` 为"店铺、标题和内容不能为空"。

**实现流程：接口内**（在一个事务里）

1. 校验必填项。
2. 由后端设置作者（当前用户）、点赞数 0、评论数 0、发布时间（精确到秒），忽略请求里传来的这些字段。
3. 插入 `tb_blog`。
4. 统计作者的粉丝数（最多数到 10000 就停）：达到 10000 记为拉模式（`feed_mode = 1`），否则为推模式（`feed_mode = 0`）；同时把投递状态设为待投递（`feed_status = 1`）。
5. 返回笔记 id。

**实现流程：后台投递**（`HybridFeedService.deliverPending`，每秒一次）

1. 查出待投递且到期的笔记，逐篇锁住这行处理。
2. **拉模式**：写进作者的发件箱，标记完成。
3. **推模式**：从 `feed_cursor` 之后按 `tb_follow.id` 取 500 个粉丝，用 pipeline 写进每个人的收件箱（`feed_add.lua`：加入、裁剪到 1000 条、续期 20 分钟），把 `feed_cursor` 更新为这批最后一个关注记录的 id。不足 500 个说明投完了，标记完成；否则下一秒继续投下一批。
4. 失败时 5 秒后重试，从 `feed_cursor` 接着投。

**设计要点**

- **笔记和投递任务一起提交**：投递状态和笔记在同一个事务里写入。事务提交了，任务就一定在；进程崩溃重启后，定时任务会接着投。
- **分批投递并记录进度**：粉丝很多时一次写不完，`feed_cursor` 记录投到了哪里，失败后不用从头开始。
- **重复写入没关系**：同一条笔记写进同一个收件箱两次，ZSet 会自动去重。

---

### 3.4.7 `PUT /blog/like/{id}` 点赞 / 取消点赞

**作用**：给笔记点赞或取消点赞，返回最新的点赞数。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `BlogController.likeBlog` → `LikeConsistencyService.setLiked` |

**请求参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | 路径 | long | 是 | 笔记 id |
| `liked` | query | boolean | 是 | `true` 点赞，`false` 取消 |

**请求示例**：`PUT /blog/like/12?liked=true`

**返回示例**

```json
{ "success": true, "data": { "liked": 9, "isLike": true } }
```

| errorMsg | 原因 |
|---|---|
| 笔记不存在 | id 对应的笔记不存在 |
| 点赞记录缺失，请先恢复 Redis 备份后重试 | Redis 中的点赞名单和元数据对不上（数据丢失） |
| 点赞服务暂不可用，请重试相同操作 | Redis 或 MySQL 出错 |

**Redis 里存了什么**

| Key | 类型 | 内容 |
|---|---|---|
| `blog:liked:{id}` | ZSet | 点赞名单：member 是用户 id，score 是点赞时间戳 |
| `likes:meta:blog:{id}` | Hash | `baseline` 历史基数、`members` 名单人数、`version` 版本号 |
| `likes:dirty` | ZSet | 待写回 MySQL 的目标（如 `blog:12`），score 是下次处理时间 |

点赞数 = `baseline` + 名单人数。

**实现流程：接口内**

1. 按主键查询 `tb_blog`（**不加锁**），确认笔记存在，并看它是否已经初始化过新版点赞（`like_initialized`）。
2. 执行 `like_state.lua`，原子完成：
   1. **首次初始化**：元数据不存在、MySQL 也没初始化过时，写入元数据，`baseline = max(MySQL 点赞数 - 名单人数, 0)`。
   2. **数据校验**：名单的实际人数和元数据记录的不一致，说明 Redis 数据丢失，拒绝写入。
   3. **修改名单**：`liked=true` 执行 `ZADD NX`（已经点过就不变）；`liked=false` 执行 `ZREM`。
   4. 名单确实变了就把 `version` 加 1，更新 `members`。
   5. 把 `blog:{id}` 加入 `likes:dirty`。
   6. 返回 `baseline + 名单人数`。
3. 第一次点赞时把 `tb_blog.like_initialized` 设为 1（之后不再执行）。
4. 返回点赞数和点赞状态。

**实现流程：后台写回 MySQL**（`LikeConsistencyService.syncPending`，每秒一次）

1. 从 `likes:dirty` 取出到期的目标，每批 100 个。
2. 在事务里锁住笔记行，用 `like_snapshot.lua` 读出当前版本和点赞数，执行 `UPDATE tb_blog SET liked = 点赞数`。
3. 提交后执行 `like_ack.lua`：版本没变才把目标从 `likes:dirty` 删掉；版本变了说明同步期间又有人点赞，留到下一轮再同步。
4. 失败时 5 秒后重试。

**设计要点**

- **传目标状态，而不是"切换"**：如果接口语义是"点一下就切换"，网络超时后前端重试，就会把刚点的赞又取消掉。传 `liked=true/false`，重复请求的结果都一样（幂等）。
- **写回 MySQL 用绝对值**：写的是 `liked = 9`，而不是 `liked = liked + 1`，重试多少次都不会多加。
- **请求不锁数据库行**：如果每次点赞都 `SELECT ... FOR UPDATE`，热门笔记的点赞会排队等同一行锁，还会占住数据库连接。并发安全交给 Lua 脚本，写回 MySQL 交给后台。
- **版本号的作用**：后台同步时读到的是版本 5 的点赞数，提交前又有人点赞变成版本 6；如果不看版本就删除待同步任务，版本 6 的变化就永远不会写回 MySQL。
- **baseline 的作用**：旧数据只有 MySQL 里的点赞数，没有点赞名单。保留差值作为历史基数，点赞数就不会在迁移后突然变少。

---

### 3.4.8 `GET /blog/likes/{id}` 点赞用户列表

**作用**：返回最早给这篇笔记点赞的 5 个用户，用于详情页的头像列表。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `BlogController.queryBlogLikes` → `BlogServiceImpl.queryBlogLikes` |

**请求参数**：路径参数 `id`（笔记 id）。

**返回示例**

```json
{ "success": true, "data": [ { "id": 2, "nickName": "可可今天不吃肉", "icon": "/imgs/icons/kkjtbcr.jpg" }, { "id": 1, "nickName": "小鱼同学", "icon": "/imgs/blogs/blog1.jpg" } ] }
```

**实现流程**

1. `ZRANGE blog:liked:{id} 0 4`：score 是点赞时间戳，从小到大取前 5 个，就是最早点赞的 5 人。
2. MySQL `WHERE id IN (...) ORDER BY FIELD(id, ...)` 查询用户，保持点赞的先后顺序。
3. 转成 `UserDTO` 返回。

---

## 3.5 评论

### 模块概览

| 项目 | 内容 |
|---|---|
| 数据表 | `tb_blog_comments` |
| Redis | `blog:comment:liked:{id}` 点赞名单、`likes:meta:comment:{id}` 点赞元数据（与笔记点赞共用 `likes:dirty`） |
| 核心知识点 | 两级评论结构、批量查询避免 N+1、软删除 |
| 代码 | `BlogCommentsController`、`BlogCommentsServiceImpl` |

**两级评论结构**

```
笔记
 ├─ 一级评论 A        parent_id = 0
 │    ├─ 回复 B       parent_id = A，answer_id = A   （回复 A）
 │    └─ 回复 C       parent_id = A，answer_id = B   （回复 B，仍挂在 A 下面）
 └─ 一级评论 D        parent_id = 0
```

- `parent_id`：回复挂在哪条一级评论下面；一级评论为 0。
- `answer_id`：回复的是哪一条，可以是一级评论本身，也可以是同一楼里的另一条回复。
- `status`：0 正常，3 已删除。列表和点赞只认 `status = 0`。

**返回的评论对象**

| 字段 | 说明 |
|---|---|
| `id`、`blogId`、`userId`、`parentId`、`answerId`、`content`、`liked`、`status`、`createTime` | 表里的字段 |
| `userName` / `userIcon` | 评论者的昵称和头像（查询时填充） |
| `answerName` | 被回复的人的昵称，只有回复才有 |
| `isLike` | 当前用户是否点过赞 |
| `replies` | 回复列表，只有一级评论才有 |

---

### 3.5.1 `GET /blog-comments/list` 评论列表

**作用**：分页查询一篇笔记的一级评论，每条一级评论附带它下面的全部回复。

| 项目 | 内容 |
|---|---|
| 是否登录 | 否 |
| 每页条数 | 5 条一级评论 |
| 代码位置 | `BlogCommentsController.queryComments` → `BlogCommentsServiceImpl.queryComments` |

**请求参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `blogId` | query | long | 是 | 笔记 id |
| `current` | query | int | 否 | 页码，默认 1 |

**返回示例**（`total` 是一级评论总数）

```json
{
  "success": true,
  "data": [
    {
      "id": 1, "blogId": 12, "userId": 1, "parentId": 0, "answerId": 0,
      "content": "看起来不错！", "liked": 2, "status": 0, "createTime": "2026-10-23T11:00:00",
      "userName": "小鱼同学", "userIcon": "/imgs/blogs/blog1.jpg", "isLike": false,
      "replies": [
        {
          "id": 3, "blogId": 12, "userId": 2, "parentId": 1, "answerId": 1,
          "content": "确实好吃", "liked": 0, "status": 0, "createTime": "2026-10-23T11:05:00",
          "userName": "可可今天不吃肉", "userIcon": "/imgs/icons/kkjtbcr.jpg", "answerName": "小鱼同学", "isLike": false
        }
      ]
    }
  ],
  "total": 6
}
```

**实现流程**

1. 分页查询一级评论：`blog_id = ? AND parent_id = 0 AND status = 0`，最新的在前。
2. **一次** `IN` 查询这一页所有一级评论的回复（`status = 0`），按时间正序。
3. 批量填充：
   - 一次 `IN` 查询所有评论者，填入 `userName`、`userIcon`；
   - `answerName`：在本页已查出的评论里找到被回复的那条，取它作者的昵称；
   - 点赞数和点赞状态：Redis pipeline 一次读出（同 3.4 的公共步骤）。
4. 回复按 `parentId` 分组，挂到对应一级评论的 `replies` 上。

**设计要点**

- **2 次查询代替 N+1**：先查一页一级评论，再用一次 `IN` 查出它们的全部回复，在内存里分组。如果每条一级评论单独查回复，一页 5 条就要 6 次查询。
- **回复不分页**：一条一级评论下回复很多时，会一次全部返回。

---

### 3.5.2 `POST /blog-comments` 发表评论或回复

**作用**：发表一级评论，或者回复某条评论。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 请求格式 | JSON |
| 代码位置 | `BlogCommentsController.saveComment` → `BlogCommentsServiceImpl.saveComment` |

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `blogId` | long | 是 | 笔记 id |
| `content` | string | 是 | 内容，去掉首尾空格后 1 ～ 255 个字符 |
| `parentId` | long | 否 | 回复时填所属的一级评论 id；不填或 0 表示一级评论 |
| `answerId` | long | 否 | 回复时填被回复的那条评论 id |

**请求示例**（回复评论 3，它挂在一级评论 1 下面）

```json
{ "blogId": 12, "content": "同意", "parentId": 1, "answerId": 3 }
```

**返回示例**：`{ "success": true, "data": 7 }`（评论 id）

| errorMsg | 原因 |
|---|---|
| 评论内容不能为空 | 没传 `blogId` 或内容为空 |
| 评论内容不能超过255个字符 | 内容太长 |
| 笔记不存在 | `blogId` 不存在 |
| 回复的一级评论不存在 | `parentId` 不存在、已删除、不属于这篇笔记，或者它本身是回复 |
| 被回复的评论不存在 | `answerId` 不存在、已删除，或者不在这条一级评论的楼里 |

**实现流程**（在一个事务里）

1. 校验内容，确认笔记存在。
2. 如果是回复（`parentId > 0`）：
   - 锁住一级评论（`FOR UPDATE`），检查它正常、属于同一篇笔记、本身是一级评论；
   - `answerId > 0` 时，检查被回复的评论正常、属于同一篇笔记，并且是这条一级评论本身或它下面的回复。
3. 如果是一级评论，把 `answerId` 置为 0。
4. 由后端设置作者、点赞数 0、状态 0，插入 `tb_blog_comments`。
5. `tb_blog.comments` 加 1。

**设计要点**

- **为什么锁一级评论**：删除评论（3.5.4）也会锁这一行。加锁后"回复"和"删除"不会同时进行，避免把回复挂到一条刚被删除的评论下面。
- **不信任前端传来的关系**：`parentId`、`answerId` 都要在后端校验，否则可以把回复挂到别的笔记下面。

---

### 3.5.3 `PUT /blog-comments/like/{id}` 评论点赞 / 取消点赞

**作用**：给评论点赞或取消点赞。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `BlogCommentsController.likeComment` → `LikeConsistencyService.setLiked` |

**请求参数**：路径参数 `id`（评论 id），query 参数 `liked`（`true` / `false`，必填）。

**返回示例**：`{ "success": true, "data": { "liked": 3, "isLike": true } }`

**实现流程**：和笔记点赞（3.4.7）是同一套代码，区别只有：

| | 笔记点赞 | 评论点赞 |
|---|---|---|
| 点赞名单 | `blog:liked:{id}` | `blog:comment:liked:{id}` |
| 元数据 | `likes:meta:blog:{id}` | `likes:meta:comment:{id}` |
| 待同步目标 | `blog:{id}` | `comment:{id}` |
| 写回的表 | `tb_blog.liked` | `tb_blog_comments.liked` |
| 额外检查 | — | 评论必须是正常状态，否则返回"评论不存在" |

---

### 3.5.4 `DELETE /blog-comments/{id}` 删除评论

**作用**：删除自己的评论。删除一级评论时，它下面的回复一起删除。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `BlogCommentsController.deleteComment` → `BlogCommentsServiceImpl.deleteComment` |

**请求参数**：路径参数 `id`（评论 id）。

**返回示例**：`{ "success": true }`

| errorMsg | 原因 |
|---|---|
| 评论不存在 | 评论不存在或已经删除 |
| 只能删除自己的评论 | 不是自己发的评论 |

**实现流程：接口内**（在一个事务里）

1. 锁住这条评论，检查它存在、状态正常。
2. 检查是不是自己发的。
3. 锁住要删除的所有行：这条评论本身，加上 `parent_id` 等于它的回复。
4. **软删除**：把这些行的 `status` 设为 3，`like_cleanup_pending` 设为 1。
5. `tb_blog.comments` 减去删除的条数（不会减到负数）。

**实现流程：后台清理**（`LikeConsistencyService.syncPending`，每秒一次）

查出 `like_cleanup_pending = 1` 的评论，删除它们在 Redis 里的点赞名单和元数据，再把标记清零。

**设计要点**

- **为什么软删除**：评论的点赞数据在 Redis 里，删除 MySQL 记录后还要去清理 Redis。保留这一行并打上"待清理"标记，标记和删除在同一个事务里提交，后台一定能找到要清理的数据。
- **为什么评论点赞的后台同步不会把它"复活"**：同步时会检查评论状态，发现已删除就直接清理，不再写回点赞数。

---

## 3.6 关注

### 模块概览

| 项目 | 内容 |
|---|---|
| 数据表 | `tb_follow`（关注关系，唯一索引 `uk_user_follow(user_id, follow_user_id)`）、`tb_user` 的 `follow_version`（关注版本）和 `follow_sync_pending`（待同步标记） |
| Redis | `follows:{userId}` 关注集合、`follows:version:{userId}` 集合对应的版本 |
| 核心知识点 | 用版本号判断缓存是否过期、先建临时集合再原子替换 |
| 代码 | `FollowController`、`FollowServiceImpl`、`FollowCacheService` |

**公共步骤：读取某人的关注集合（`FollowCacheService.followedIds`）**

共同关注（3.6.3）和关注流（3.4.5）都会调用：

1. 查 MySQL 里这个人当前的关注版本 `tb_user.follow_version`。
2. Redis 的 `follows:version:{userId}` 和它相等，并且集合 `follows:{userId}` 里有哨兵成员 `_`：直接使用 Redis 的集合（去掉 `_`）。
3. 否则重建缓存：
   1. 锁住这个人在 `tb_user` 的行；
   2. 分批（每批 500）从 `tb_follow` 读出他关注的全部用户 id；
   3. 写进一个临时集合 `follows:building:{userId}:{随机串}`（先放入哨兵 `_`，60 秒过期）；
   4. 执行 `follow_publish.lua`，原子完成：把临时集合改名为 `follows:{userId}`，设置 10 分钟过期，写入版本号，删除 `feed:ready:{userId}`（让关注流下次重建收件箱）；
   5. 清除 `follow_sync_pending` 标记。
4. Redis 出错时，直接从 MySQL 读。

**为什么要放哨兵 `_`**：Redis 里空集合是不存在的。一个人没关注任何人时，没有哨兵就分不清"他没关注任何人"和"缓存还没建"，每次都会去查数据库。

---

### 3.6.1 `PUT /follow/{id}/{isFollow}` 关注 / 取消关注

**作用**：关注或取消关注某个用户。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `FollowController.follow` → `FollowServiceImpl.follow` |

**请求参数**

| 参数 | 位置 | 类型 | 说明 |
|---|---|---|---|
| `id` | 路径 | long | 要关注的用户 id |
| `isFollow` | 路径 | boolean | `true` 关注，`false` 取消关注 |

**请求示例**：`PUT /follow/2/true`

**返回示例**：`{ "success": true }`

| errorMsg | 原因 |
|---|---|
| 不能关注自己 | `id` 是自己 |
| 用户不存在 | 要关注的用户不存在 |

**实现流程**（在一个事务里）

1. 检查不是关注自己；关注时确认对方存在。
2. `SELECT ... FOR UPDATE` 锁住**自己**在 `tb_user` 的行，同一个人的关注操作排队执行。
3. 关注：`INSERT ... ON DUPLICATE KEY UPDATE`（已经关注过也不报错）；取消：`DELETE`。
4. 自己的 `follow_version` 加 1，`follow_sync_pending` 设为 1。
5. 事务提交后，立刻重建自己的关注缓存（公共步骤第 3 步）。失败的话，定时任务 5 秒后重试。

**设计要点**

- **传目标状态，结果幂等**：重复发送"关注"不会报错，也不会产生重复记录（有唯一索引）。
- **用版本号判断缓存是否过期**：读缓存时和 MySQL 的版本比较，不相等就重建。即使提交后重建缓存失败，旧缓存也不会被继续使用。
- **先建临时集合再改名**：重建过程中，其他请求读到的要么是旧的完整集合，要么是新的完整集合，不会读到只写了一半的集合。
- **影响关注流**：重建关注缓存时会删除 `feed:ready:{userId}`，下次打开关注流就会补进新关注的人的笔记、删掉取关的人的笔记。

**注意**：`tb_user_info` 里的粉丝数、关注数不会随这个接口变化。

---

### 3.6.2 `GET /follow/or/not/{id}` 是否已关注

**作用**：判断当前用户是否关注了某人，用于显示"关注 / 取消关注"按钮。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `FollowController.isFollow` → `FollowServiceImpl.isFollow` |

**请求参数**：路径参数 `id`（对方的用户 id）。

**返回示例**：`{ "success": true, "data": true }`

**实现流程**：MySQL 查询 `tb_follow` 中 `user_id = 自己 AND follow_user_id = 对方` 的记录数，大于 0 返回 `true`。这个条件正好走唯一索引 `uk_user_follow`。

---

### 3.6.3 `GET /follow/common/{id}` 共同关注

**作用**：查询我和某人都关注了的用户。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `FollowController.followCommons` → `FollowServiceImpl.followCommons` |

**请求参数**：路径参数 `id`（对方的用户 id）。

**返回示例**

```json
{ "success": true, "data": [ { "id": 2, "nickName": "可可今天不吃肉", "icon": "/imgs/icons/kkjtbcr.jpg" } ] }
```

**实现流程**

1. 分别读取我和对方的关注集合（公共步骤）。
2. 在 Java 里求交集。
3. 一次 `IN` 查询交集里的用户，转成 `UserDTO` 返回。

**设计要点**：没有直接用 Redis 的 `SINTER`，因为每个集合都要先和 MySQL 版本比较、必要时重建，所以先分别读出来再在内存里求交集。

---

## 3.7 上传

### 模块概览

| 项目 | 内容 |
|---|---|
| 存储位置 | 后端启动目录下的 `uploads/blogs/`（`SystemConstants.IMAGE_UPLOAD_DIR`） |
| 访问方式 | `MvcConfig` 把 `/imgs/**` 映射到 `uploads/` 目录，图片地址为 `/imgs/blogs/...` |
| 代码 | `UploadController` |

**注意**：存储位置取决于启动目录（`user.dir`），必须在项目根目录启动后端，否则图片会存到别的地方，`/imgs/**` 也找不到。

---

### 3.7.1 `POST /upload/blog` 上传笔记图片

**作用**：上传一张图片，返回保存后的相对路径，发布笔记时放进 `images` 字段。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 请求格式 | `multipart/form-data` |
| 代码位置 | `UploadController.uploadImage` |

**请求参数**

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | 文件 | 图片，扩展名只能是 jpg、jpeg、png、gif、webp |

**返回示例**

```json
{ "success": true, "data": "/blogs/3/7/5f0e2c1a-8b9d-4e6f-a1b2-c3d4e5f6a7b8.jpg" }
```

前端显示时补上前缀：`/api/imgs/blogs/3/7/...jpg`（`mediaUrl` 函数）。

不符合格式时 `errorMsg` 为"只能上传 jpg、png、gif、webp 格式的图片"。

**实现流程**

1. 检查扩展名在白名单里，并且 Content-Type 以 `image/` 开头。
2. 生成 UUID 作为文件名；用 UUID 的 hashCode 算出两级目录：`d1 = hash & 0xF`，`d2 = (hash >> 4) & 0xF`（各 0 ～ 15）。
3. 保存到 `uploads/blogs/{d1}/{d2}/{uuid}.{扩展名}`，目录不存在就创建。
4. 返回相对路径 `/blogs/{d1}/{d2}/{uuid}.{扩展名}`。

**设计要点**

- **为什么分两级目录**：所有图片放在一个目录下，文件多了以后文件系统查找会变慢。按哈希分成 16 × 16 = 256 个目录，文件均匀分布。
- **为什么限制扩展名**：不限制的话可以上传 HTML 文件，再通过 `/imgs/**` 访问，在本站域名下执行脚本（XSS）。

---

### 3.7.2 `GET /upload/blog/delete` 删除笔记图片

**作用**：删除已上传的图片，用于发布笔记前移除选错的图片。

| 项目 | 内容 |
|---|---|
| 是否登录 | 是 |
| 代码位置 | `UploadController.deleteBlogImg` |

**请求参数**：`name`（query，3.7.1 返回的相对路径）。

**请求示例**：`GET /upload/blog/delete?name=/blogs/3/7/5f0e2c1a-....jpg`

**返回示例**：`{ "success": true }`。路径不合法时 `errorMsg` 为"错误的文件名称"。

**实现流程**

1. 把 `name` 拼到上传目录后面，并**规范化**路径（把 `../` 这类跳转算出来）。
2. 规范化后的路径必须仍在 `uploads/blogs/` 目录里，并且不是目录，否则拒绝。
3. 删除文件。

**设计要点**

- **路径穿越防护**：如果直接拼接，`name=../../application.yaml` 就能删掉任意文件。先规范化再检查前缀，才能保证只删上传目录里的文件。

**注意**：只校验路径，不校验图片是不是当前用户上传的。

---

## 附录 A：后台定时任务与启动预热

### A.1 定时任务

几个接口把耗时或可能失败的操作交给后台完成。任务状态都记在 MySQL 或 Redis 里，进程重启后会接着执行。

| 任务 | 代码 | 频率 | 做什么 | 相关接口 |
|---|---|---|---|---|
| 商户缓存同步 | `ShopCacheSyncService.retryPending` | 每 1 秒 | 处理 `cache_sync_stage` 为 1、2 的商户：删除缓存、移动 GEO | 3.2.2、3.2.3 |
| 笔记投递 | `HybridFeedService.deliverPending` | 每 1 秒 | 处理 `feed_status = 1` 的笔记：写收件箱或发件箱 | 3.4.6 |
| 点赞计数写回 | `LikeConsistencyService.syncPending` | 每 1 秒 | 把 `likes:dirty` 里的点赞数写回 MySQL；清理已删除评论的点赞数据 | 3.4.7、3.5.3、3.5.4 |
| 关注缓存同步 | `FollowCacheService.retryPending` | 每 1 秒 | 为 `follow_sync_pending = 1` 的用户重建关注缓存 | 3.6.1 |
| 布隆过滤器重建 | `ShopBloomFilter.scheduledRebuild` | 每天 4:00 | 全量重建商户布隆过滤器，清掉已删除的商户 | 3.2.1 |

- 每批最多处理 100 条，失败的任务 5 秒后重试。参数在 `application.yaml` 的 `hmdp.consistency` 下。
- 重试时间用 MySQL 的 `NOW(3)` 计算，避免应用服务器和数据库时区不同导致任务迟迟不执行。

### A.2 启动预热（`StartupDataInitializer`）

后端启动完成后执行一次：

1. **布隆过滤器**：不存在或快满了才重建。
2. **商户**：每批 500 个，给所有商户登记一次缓存同步，由定时任务执行（会重新写入 GEO）。
3. **秒杀**：
   - 库存用 `SETNX` 写入 Redis，已经存在就不覆盖（运行中的库存比数据库更准）；
   - 写入活动时间；
   - 按订单表回填已购用户集合。
   - 这一步失败时启动失败，避免在 Redis 数据不完整的情况下接受秒杀请求。
4. **关注和关注流不预热**，用户访问时按需加载。

---

## 附录 B：Lua 脚本一览

所有脚本在 `src/main/resources/` 下。用 Lua 的原因相同：多条 Redis 命令需要作为一个整体执行，中间不能被其他请求插入。

| 脚本 | 调用方 | 作用 |
|---|---|---|
| `seckill.lua` | 秒杀下单（3.3.4） | 检查活动时间、库存、一人一单，通过后扣库存 |
| `seckill_rollback.lua` | 秒杀失败时 | 把用户移出已购集合，按情况加回或清零库存 |
| `limiter.lua` | `@RateLimiter` 切面 | 滑动窗口限流 |
| `unlock.lua` | `CacheClient` | 比较锁的值后再删除，只释放自己的锁 |
| `shop_sync.lua` | 商户缓存同步 | 删除详情缓存，把商户移到新分类的 GEO 集合 |
| `like_state.lua` | 点赞（3.4.7、3.5.3） | 修改点赞名单、版本号，登记待同步 |
| `like_snapshot.lua` | 列表查询、点赞写回 | 读取点赞数和当前用户是否点过赞 |
| `like_ack.lua` | 点赞写回 | 版本号没变才删除待同步任务 |
| `feed_add.lua` | 笔记投递 | 把笔记加入收件箱或发件箱，裁剪到 1000 条并续期 |
| `feed_read.lua` | 关注流（3.4.5） | 按游标倒序读取 |
| `follow_publish.lua` | 关注缓存重建 | 用临时集合原子替换关注集合，写入版本号 |
