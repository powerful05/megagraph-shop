# MegaGraph Shop

MegaGraph Shop 已完成阶段 1 商品发布和阶段 2A–2C 交易、履约、未发货退款纵切片，并进入阶段 2D 异步支付基础层：

```text
商家登录 → 上传商品图片 → Mock AI 生成草稿 → 补齐类目/SKU/价格/库存
→ 确认 → 发布 → 用户端可见 → 下架后立即不可见
```

当前实现把 AI 生成器和媒体存储都放在接口后面：本地开发默认使用 Mock AI、H2 和本地文件存储；后续可以替换为真实多模态模型、MySQL 与 MinIO，而不改变商品发布规则。

阶段 2A 同样不依赖外部密钥：本地使用 Mock 支付渠道，围绕购物车、订单、库存预占和支付状态建立可测试的交易内核。Nuxt 顾客端已接入购物车、结算、订单与 Mock 支付，Vue 商家端已提供订单台账和交易指标。范围和验收标准见 `docs/PHASE_2A.md`。

阶段 2B 继续使用本地事实：商家可以为已支付订单填写承运商与物流单号，顾客可查看物流并确认收货。范围和验收标准见 `docs/PHASE_2B.md`。

阶段 2C 使用接口化的 Mock 退款通道：顾客可为已支付未发货订单申请退款，商家审核后退款并回补库存，或驳回后继续履约。范围和验收标准见 `docs/PHASE_2C.md`。

阶段 2D 将支付和退款改为异步渠道事件模型：原始回调先验签，再经 Inbox 去重和状态机推进订单、库存与 Outbox。本地 Mock 也走同一回调链路，但仍不依赖微信、支付宝、Stripe 等任何真实凭据。两个前端展示处理中/失败/待核查状态，并使用有界轮询获取后端事实。范围和验收标准见 `docs/PHASE_2D.md`。

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
  PHASE_2B.md    阶段 2B 订单履约纵切片
  PHASE_2C.md    阶段 2C 未发货退款纵切片
  PHASE_2D.md    阶段 2D 异步支付与退款基础层
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
- `POST /api/shop/v1/orders/{orderId}/mock-payment`：在 `local` / `test` 中提交异步 Mock 支付结果，需要 `Idempotency-Key`；返回时不代表支付终态。
- `POST /api/shop/v1/orders/{orderId}/cancel`：取消待支付订单。
- `GET /api/merchant/v1/orders`：查询当前商家的订单。

## 阶段 2B API

- `POST /api/merchant/v1/orders/{orderId}/ship`：商家为已支付订单创建物流单，需要 `Idempotency-Key`。
- `POST /api/shop/v1/orders/{orderId}/confirm-receipt`：顾客确认收货并完成订单。

## 阶段 2C API

- `POST /api/shop/v1/orders/{orderId}/after-sales`：顾客为已支付未发货订单申请退款，需要 `Idempotency-Key`。
- `POST /api/merchant/v1/orders/{orderId}/after-sales/approve`：商家批准并提交异步 Mock 退款，需要 `Idempotency-Key`；首次响应是退款处理中。
- `POST /api/merchant/v1/orders/{orderId}/after-sales/reject`：商家驳回退款申请，需要 `Idempotency-Key`。

## 阶段 2D API

- `POST /api/payment-callbacks/{provider}`：免会话认证的渠道回调入口，但必须通过 provider 对应的原始报文验签。
- `POST /api/merchant/v1/orders/{orderId}/after-sales/refund-retry`：OWNER 对失败退款显式重试，必须使用新的 `Idempotency-Key`。
- 顾客订单查询和商家订单列表是支付/退款状态的读取入口；前端不伪造终态。

## 验证

```bash
pnpm verify
pnpm -r --if-present test
```

`pnpm verify` 会运行前端类型检查和生产构建、H2 后端集成测试与后端打包。共享状态单元测试由第二条命令执行。

真实 MySQL 8.4 迁移冒烟必须使用专用的空 schema：

```bash
MYSQL_SMOKE_URL='jdbc:mysql://127.0.0.1:3307/megagraph_smoke' \
MYSQL_SMOKE_USERNAME='megagraph' \
MYSQL_SMOKE_PASSWORD='local-password' \
apps/backend/scripts/mysql-migration-smoke.sh
```

脚本会拒绝非空 schema，不会清理或复用现有业务数据。未提供 MySQL 8.4 连接时，常规 Maven 测试会将该冒烟用例标记为 skipped，不应将它报告为已通过。

项目显式使用 Maven Central，避免工作站级镜像不可用时阻塞构建；不会修改全局 Maven 配置。

## 生产依赖切换

本地 profile 使用 H2 与 `./var/uploads`。MySQL 配置示例见 `application-mysql.yml`；MinIO 适配器将在接入真实 AI Worker 时实现。`compose.yml` 提供 MySQL 和 MinIO 的开发环境定义，但不是本地运行的硬依赖。
