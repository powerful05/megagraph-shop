# MegaGraph Shop

MegaGraph Shop 阶段 1 是一条可运行的商品发布垂直切片：

```text
商家登录 → 上传商品图片 → Mock AI 生成草稿 → 补齐类目/SKU/价格/库存
→ 确认 → 发布 → 用户端可见 → 下架后立即不可见
```

当前实现把 AI 生成器和媒体存储都放在接口后面：本地开发默认使用 Mock AI、H2 和本地文件存储；后续可以替换为真实多模态模型、MySQL 与 MinIO，而不改变商品发布规则。

## 工程结构

```text
apps/
  backend/       Spring Boot 模块化业务应用
  merchant-web/  Vue 3 + Vite 商家工作台
  shopper-web/   Nuxt 用户端与品牌官网入口
packages/
  domain-types/  前端共享领域类型
docs/
  PHASE_1.md     阶段 1 范围、决策和验收标准
```

## 本地启动

要求：Java 21、Maven 3.9+、Node 22+、pnpm 10+。

```bash
pnpm install
mvn -s .mvn/settings.xml -f apps/backend/pom.xml spring-boot:run
```

另开终端：

```bash
pnpm dev:merchant
pnpm dev:shopper
```

- 商家端：http://localhost:5173
- 用户端：http://localhost:3000
- API：http://localhost:8080
- 本地开发账号：`merchant@megagraph.local`
- 本地开发密码：`merchant123`

本地账号只由 `local` profile 的启动器创建，不写入数据库迁移，也不得用于部署环境。

## 验证

```bash
pnpm typecheck
pnpm build
mvn -s .mvn/settings.xml -f apps/backend/pom.xml test
mvn -s .mvn/settings.xml -f apps/backend/pom.xml package -DskipTests
```

项目显式使用 Maven Central，避免工作站级镜像不可用时阻塞构建；不会修改全局 Maven 配置。

## 生产依赖切换

本地 profile 使用 H2 与 `./var/uploads`。MySQL 配置示例见 `application-mysql.yml`；MinIO 适配器将在接入真实 AI Worker 时实现。`compose.yml` 提供 MySQL 和 MinIO 的开发环境定义，但不是本地运行的硬依赖。
