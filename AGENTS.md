# AGENTS.md
## 项目概述
`hm-dianping` 是一个本地生活服务后端示例，核心能力覆盖用户登录、店铺检索、博客互动与优惠券秒杀下单。
## 开发命令
- 安装依赖：`mvn clean compile`
- 本地启动：`mvn spring-boot:run`
- 构建打包：`mvn clean package -DskipTests`
- 运行测试：`mvn test`
## 关键目录
- `src/main/java/com/hmdp/controller/` - API 入口层
- `src/main/java/com/hmdp/service/` - 业务接口与实现
- `src/main/java/com/hmdp/mapper/` - 数据访问层
- `src/main/resources/` - 配置、SQL 与 Lua 脚本
- `src/test/java/com/hmdp/` - 测试代码
## 边界约束
- 仅依据代码已存在事实编写技术文档，不臆造路径、命令、接口。
- 业务规则与历史经验优先写入 `manual/`，由团队持续维护。
- 秒杀链路涉及 Redis Lua 与异步消费，改动时需同时验证 Redis 与数据库一致性。
## AI 上下文
详细规则见 `.cursor/rules/ai-readme/RULE.mdc`：
- 架构：`.cursor/rules/ai-readme/generated/技术架构.mdc`
- 流程：`.cursor/rules/ai-readme/generated/核心流程.mdc`
- 业务（人工维护）：`.cursor/rules/ai-readme/manual/业务知识.mdc`
- 踩坑（人工维护）：`.cursor/rules/ai-readme/manual/历史经验.mdc`
