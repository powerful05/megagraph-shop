# 阶段 2D 集成验收记录

验收日期：2026-09-26。基线：`codex/phase-2d-integration` 的 `2fbff9d`。本记录只证明本阶段的 Mock 异步支付基础；不代表真实支付、生产身份认证或商家结算已可上线。

## 自动验证

- `git diff --check main..codex/phase-2d-integration` 通过；数据库历史迁移 `V1`–`V4` 未修改，只新增 `V5__async_payment_foundation.sql`。
- 根目录 `pnpm verify` 通过：前端类型检查、两个前端生产构建、后端 H2 集成测试和后端打包均成功。Maven 汇总为 19 项、0 失败、0 错误，其中 MySQL 专用用例在常规运行中按设计跳过 1 项。
- `pnpm -r --if-present test` 通过，共享状态单元测试 5 项成功。
- `CommerceFlowIntegrationTest#mockPaymentIsAcceptedAsProcessingBeforeDurableCommandAppliesTerminalState` 额外连续运行 5 次通过，用于复查过期 `PROCESSING` 命令的时间精度和立即领取问题。
- 在 MySQL 8.4.11 的专用空 schema 上运行 `apps/backend/scripts/mysql-migration-smoke.sh`，`V1`–`V5` 全部迁移并通过 Flyway `validate`；MySQL 专用测试 1 项通过、0 跳过。另在独立空 schema 上以 `local,mysql` 启动完整后端，迁移成功，重启后仍通过 Flyway 校验。

## MySQL 与浏览器闭环

验收使用独立 schema `megagraph_smoke_2d_20260926` 和 `megagraph_runtime_2d_20260926`，未清理或复用既有业务 schema。运行时测试在 `local,mysql` 配置中显式设置 `APP_DEV_SEED_ENABLED=true`，因为 `application-mysql.yml` 默认关闭开发账号种子。商品为该专用 schema 内的测试数据，非真实上架商品。

1. 顾客端创建订单后，SKU 可售库存从 8 降至 7，订单预占为 `RESERVED`；点击 Mock 成功后先显示 `PROCESSING`，再由回调和轮询变为订单 `PAID`、支付 `SUCCEEDED`、预占 `CONFIRMED`。
2. 顾客申请未发货退款，商家批准后两端先显示 `REFUND_PROCESSING`，订单保持已支付、库存不提前回补；回调成功后订单/支付为 `REFUNDED`、退款单为 `SUCCEEDED`、预占为 `RESTOCKED`，SKU 可售库存恢复到 8。MySQL 中的回调 Inbox 与各类型业务 Outbox 事件数量符合一次状态迁移的预期。
3. 第二笔订单的 Mock 支付失败同样先显示处理中，再变为 `PAYMENT_FAILED`；预占为 `RELEASED`，库存恢复到 8。
4. 第三笔已支付订单用于退款失败/重试。为避免默认 Mock 命令立即产生成功回调，验收时仅在专用测试 schema 中暂停命令调度、绑定测试渠道退款单号并发送合法签名的 `REFUND_FAILED` 回调。此时订单/支付仍成功、预占仍 `CONFIRMED`、库存为 7，商家端显示失败摘要和 OWNER 重试入口。显式重试后恢复命令调度并重启后端，耐久命令完成，最终退款成功、库存恢复到 8。
5. 顾客端页面重载并重新连接本地测试账号后，三笔订单均从 MySQL 恢复正确终态。商家端轮询超过 30 秒时提示手动刷新，不把超时误报为退款失败。
6. 对同一合法回调重放，返回 `200` 且 `duplicate=true`，不重复生成退款失败 Outbox；同一事件 ID 的不同报文返回 `409 EVENT_ID_PAYLOAD_MISMATCH`，不改变已完成的订单、退款和库存事实；缺少签名返回 `401`。
7. MySQL 实际拒绝重复的支付渠道单号、退款渠道单号和同一退款单的重试幂等键（均为 `1062`）；Inbox 重复事件由迁移冒烟用例验证。将一条已完成的测试命令改为过期 `PROCESSING` 后，MySQL 运行中的调度器将其重新领取并完成，尝试次数从 1 到 2，Inbox 和 `ORDER_PAID` Outbox 仍各只有一份。
8. 第二商家/顾客测试身份读取其他顾客订单和操作其他商家退款均得到 `404`，商家订单列表为空。仅启用 `mysql` 的非本地配置时，Mock 回调和顾客 Mock 支付入口均返回 `404`。

## 已知边界

- 默认本地 Mock 退款总是成功；浏览器退款失败路径需要上述受控测试注入。本次未增加生产故障注入端点。
- Flyway 在 MySQL 8.4.11 上迁移和校验均成功，但当前依赖版本打印“最新已测试 MySQL 为 8.1”的兼容性提醒；后续升级依赖时需重新运行迁移冒烟。
- 本阶段没有接入真实渠道凭据。顾客端重载后需重新连接本地 Basic Auth 测试会话；正式身份会话属于阶段 2E。
