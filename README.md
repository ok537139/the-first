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

打开 <http://127.0.0.1:5173>。前端开发服务器通过 `/api` 代理到 <http://127.0.0.1:8081>。

## 演示数据

`src/main/resources/db/demo-data.sql` 会在新建 MySQL 数据卷时自动执行。已有数据卷可手动执行：

```powershell
Get-Content -Raw -Encoding UTF8 .\src\main\resources\db\demo-data.sql | docker exec -i hm-dianping-mysql mysql -uroot -p123456 --default-character-set=utf8mb4
```

脚本可重复执行，包括 22 家商户、用户资料、关注关系、优惠券、秒杀库存、订单与评论数据。

## 构建

```powershell
mvn clean package -DskipTests
cd frontend
npm run build
```
