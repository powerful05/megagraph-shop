# MegaGraph Shop

MegaGraph Shop 已完成阶段 1 商品发布闭环，并打通阶段 2A 前后端交易纵切片：

```text
商家登录 → 上传商品图片 → Mock AI 生成草稿 → 补齐类目/SKU/价格/库存
→ 确认 → 发布 → 用户端可见 → 下架后立即不可见
```

当前实现把 AI 生成器和媒体存储都放在接口后面：本地开发默认使用 Mock AI、H2 和本地文件存储；后续可以替换为真实多模态模型、MySQL 与 MinIO，而不改变商品发布规则。

阶段 2A 同样不依赖外部密钥：本地使用 Mock 支付渠道，围绕购物车、订单、库存预占和支付状态建立可测试的交易内核。Nuxt 顾客端已接入购物车、结算、订单与 Mock 支付，Vue 商家端已提供订单台账和交易指标。范围和验收标准见 `docs/PHASE_2A.md`。

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
  PHASE_2A.md    阶段 2A 前后端交易纵切片
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
- 本地顾客账号：`shopper@megagraph.local`
- 本地顾客密码：`shopper123`

本地账号只由 `local` profile 的启动器创建，不写入数据库迁移，也不得用于部署环境。

## 阶段 2A API

- `GET /api/shop/v1/cart`：查询当前顾客购物车。
- `PUT /api/shop/v1/cart/items/{skuId}`：设置 SKU 数量。
- `DELETE /api/shop/v1/cart/items/{skuId}`：删除购物车条目。
- `POST /api/shop/v1/orders`：从购物车创建订单，需要 `Idempotency-Key`。
- `GET /api/shop/v1/orders`：查询当前顾客订单。
- `POST /api/shop/v1/orders/{orderId}/mock-payment`：模拟支付结果，需要 `Idempotency-Key`。
- `POST /api/shop/v1/orders/{orderId}/cancel`：取消待支付订单。
- `GET /api/merchant/v1/orders`：查询当前商家的订单。

## 验证

```bash
pnpm verify
```

项目显式使用 Maven Central，避免工作站级镜像不可用时阻塞构建；不会修改全局 Maven 配置。

## 生产依赖切换

本地 profile 使用 H2 与 `./var/uploads`。MySQL 配置示例见 `application-mysql.yml`；MinIO 适配器将在接入真实 AI Worker 时实现。`compose.yml` 提供 MySQL 和 MinIO 的开发环境定义，但不是本地运行的硬依赖。
