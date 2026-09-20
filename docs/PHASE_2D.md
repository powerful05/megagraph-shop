# 阶段 2D：真实支付渠道接入前的异步支付基础层

## 结论与影响

阶段 2A–2C 已经证明了订单、库存预占、履约和未发货整单退款的业务规则，但当前支付和退款仍是“HTTP 请求内同步返回结果并立即修改终态”。这不是真实支付渠道的可用基础：真实回调会重复、延迟、乱序，也可能在本地订单超时后才报告支付成功。

本阶段不是“换一个真实支付 SDK”，而是先把以下稳定边界落地：签名验证与渠道解析隔离、渠道事件 Inbox、数据库唯一约束、幂等状态迁移、状态变化与 Outbox 同事务，以及前端对“处理中”状态的真实展示与刷新。本地 Mock 必须走和未来真实渠道相同的回调入口和领域处理链路，不能再绕过回调服务直接改订单。

本阶段不承诺端到端“恰好一次”。正确的可验收目标是：渠道可以至少一次投递，系统依靠 MySQL 唯一约束、行锁和幂等状态迁移，使业务结果最多生效一次。

## 目标

- 建立统一的支付与退款回调接收链路，但保留渠道特定验签和报文解析适配器。
- 使支付、退款、订单、库存预占和售后状态只由已验签、已匹配本地事实的回调事件推进。
- 使重复回调、乱序回调、重复商家操作和进程重启不会重复扣库存、重复回库或重复生成 Outbox 业务事件。
- 用无真实密钥的 Mock 渠道完成端到端异步验收，包括合法签名、无效签名、重复事件、矛盾事件和处理失败。
- 让顾客端和商家端能区分“请求已受理”与“业务已成功”，页面刷新或进程重启后仍可从 MySQL 恢复真实状态。

## 非目标

- 不接入微信支付、支付宝、Stripe、PayPal 或其他真实渠道，不引入它们的 SDK、商户号、API Key、证书或 Webhook Secret。
- 不处理部分支付、部分退款、多次退款、退单、拒付、商家结算、渠道手续费和财务对账。
- 不用 Redis、Kafka、RabbitMQ、SSE 或 WebSocket 换取本阶段不需要的运维复杂度。
- 不让顾客端、商家端或 AI 决定支付成功、退款成功、金额、币种、库存或订单终态。
- 不把 H2 上的集成测试通过当作 MySQL 迁移可用的证据。

## 现状与必须纠正的假设

1. 当前 `POST /api/shop/v1/orders/{orderId}/mock-payment` 由顾客选择 `SUCCESS`、`FAILURE` 或 `CANCELLED`，然后同步修改支付、订单和库存预占。它只能保留为 `local/test` 的回调模拟器，不能成为真实回调 API，也不能在非本地 profile 暴露。
2. 当前商家批准退款时，`RefundGateway` 同步返回成功，同一请求内立即把支付和订单改为已退款并回补库存。真实渠道可能只返回“已受理”，所以 2D 必须把“商家批准”和“渠道退款成功”拆开。
3. 当前 `RefundStatus` 只有 `SUCCEEDED`，无法表达处理中、失败与重试；`refunded_at` 也是必填列。新迁移必须允许先建立退款单，成功回调后再填写完成时间。
4. “本地订单已超时”不代表“渠道不可能再报告成功”。超时释放库存后收到支付成功是冲突事件，不得直接恢复为已支付或忽略，必须保留事件并进入待核查状态。
5. 各渠道的签名方法、原始报文和事件类型不同，不应假设未来所有渠道会调用一个公开的统一 JSON 格式。统一的应该是验签和解析之后的内部事件，而不是外部请求体。

## 总体处理流程

```text
本地 Mock 渠道生成原始回调
  → 回调入口保留 raw body 和必要 headers
  → 渠道适配器先验签，再解析为内部事件
  → MySQL Inbox 以 (provider, provider_event_id) 去重
  → 锁定支付单或退款单并校验金额、币种、渠道单号
  → 幂等推进支付/退款/订单/库存/售后状态
  → 在同一事务写入 Outbox 业务事件和 Inbox 处理结果
  → 前端轮询订单查询 API 读取已提交事实
```

验签失败时不解析或应用业务字段，也不信任报文中的事件 ID。可记录受限的安全日志和指标，但不得把未验签的金额或状态写成业务事实。

## 支付渠道回调接收模型

外部入口使用 `POST /api/payment-callbacks/{provider}`。该路径不使用顾客或商家会话认证，但必须通过对应 provider 的签名验证；未注册的 provider 直接拒绝。本阶段只在 `local/test` profile 注册 `mock` 适配器，非本地 profile 不能把 Mock 回调当作可用支付渠道。

接口层必须把原始字节、必要请求头和路径中的 provider 交给渠道适配器，不能先把 JSON 反序列化后再重组字符串验签。验签与解析成功后生成内部 `ChannelEvent`，至少包含：

- `provider`：由路由与已注册适配器确定，不信任 body 内同名字段。
- `providerEventId`：渠道事件唯一 ID。
- `eventType`：`PAYMENT_PROCESSING`、`PAYMENT_SUCCEEDED`、`PAYMENT_FAILED`、`PAYMENT_CANCELLED`、`REFUND_PROCESSING`、`REFUND_SUCCEEDED` 或 `REFUND_FAILED`。
- `resourceType`：`PAYMENT` 或 `REFUND`。
- `providerResourceId`：渠道支付单号或退款单号。
- `occurredAt`：渠道声明的发生时间；服务端另行记录接收时间，不用它代替本地时钟。
- `amount` 和 `currency`：Mock 事件必填，并必须与本地支付单或退款单严格匹配。
- `payloadDigest`：对 raw body 计算的 SHA-256，用于识别“同一事件 ID 却报文不同”的冲突。

回调响应只表示“已接收/已拒绝”，不是顾客端的支付结果 API。已成功处理的重复事件应返回渠道所需的成功应答，不重复执行领域变更。

## 渠道事件唯一 ID 与幂等约束

新增 Inbox 表 `payment_channel_event`，至少保存 `provider`、`provider_event_id`、`event_type`、`resource_type`、`provider_resource_id`、`payload_digest`、`received_at`、`processing_status`、`processed_at`、`matched_payment_order_id`、`matched_refund_order_id`、`failure_code` 和经脱敏、受限长度的 payload。

数据库约束与处理规则如下：

- `UNIQUE(provider, provider_event_id)` 是回调去重的最终保障，不依赖先查后插的时序。
- 首次收到已验签事件时插入 Inbox；并发重复插入由唯一约束裁决，再读取已有处理结果。
- 同一 `(provider, provider_event_id)` 且 `payload_digest` 相同时是安全重放，不产生第二次状态变化或 Outbox 事件。
- 同一 `(provider, provider_event_id)` 但 `payload_digest` 不同时是冲突，不应用新报文，记录 `EVENT_ID_PAYLOAD_MISMATCH` 并报警。
- `payment_order` 继续使用 `UNIQUE(provider, provider_payment_id)`，`refund_order` 继续使用 `UNIQUE(provider, provider_refund_id)`，防止同一渠道资源被映射到两个本地聚合。
- 商家批准退款、退款重试和本地 Mock 事件生成仍使用 `Idempotency-Key`；用户请求幂等键与渠道事件 ID 是两个不同概念，不得复用同一字段。

`processing_status` 至少支持 `RECEIVED`、`APPLIED`、`REJECTED` 和 `REVIEW_REQUIRED`。重复投递直接返回首次记录的处理结果，不把原记录从 `APPLIED` 覆盖成“重复”。金额、币种、资源类型、渠道单号或当前终态不匹配时，不得强行迁移业务状态，应将事件标为 `REVIEW_REQUIRED` 并写入可观测的原因码。

## 支付异步状态机

| 对象 | 起始状态 | 事件 | 目标状态 | 同事务副作用 |
| --- | --- | --- | --- | --- |
| 支付单 | `PENDING` | 支付请求已被 Mock 渠道受理或非终态回调 | `PROCESSING` | 写入 `PAYMENT_PROCESSING` Outbox |
| 支付单 | `PENDING` / `PROCESSING` | `PAYMENT_SUCCEEDED` | `SUCCEEDED` | 订单 `PAID`，预占 `CONFIRMED`，写入 `PAYMENT_SUCCEEDED` 和 `ORDER_PAID` Outbox |
| 支付单 | `PENDING` / `PROCESSING` | `PAYMENT_FAILED` | `FAILED` | 订单 `PAYMENT_FAILED`，预占只释放一次，写入支付失败与订单取消类事件 |
| 支付单 | `PENDING` / `PROCESSING` | `PAYMENT_CANCELLED` | `CANCELLED` | 订单 `CANCELLED`，预占只释放一次，写入取消事件 |
| 支付单 | `SUCCEEDED` | 退款成功业务事件 | `REFUNDED` | 与退款、订单和库存回补一起提交 |

同一终态事件的重放是幂等成功。已进入终态后收到不同终态，或订单已是 `EXPIRED` / `CANCELLED` 且库存已释放后才收到支付成功，不覆盖现有业务事实；Inbox 标为 `REVIEW_REQUIRED`，并写入 `PAYMENT_RECONCILIATION_REQUIRED` Outbox。本阶段不自动恢复库存或发货，也不假装没有收到渠道成功事件。

订单超时任务与回调处理必须锁定同一支付单和订单，由数据库锁顺序决定唯一生效路径。超时扫描不能只凭预查结果直接改状态。

## 退款异步状态机

商家批准只表示商家决策，不表示资金已退回。批准事务必须使用本地支付单的金额、币种和渠道单号创建退款单，不接受前端金额。

| 对象 | 起始状态 | 动作或事件 | 目标状态 | 同事务副作用 |
| --- | --- | --- | --- | --- |
| 售后单 | `REQUESTED` | 商家驳回 | `REJECTED` | 订单保持 `PAID`，可继续发货，写入 `AFTER_SALE_REJECTED` |
| 售后单 / 退款单 | `REQUESTED` / 不存在 | 商家批准 | `REFUND_PROCESSING` / `PENDING` | 创建退款单和耐久化渠道命令，写入 `REFUND_REQUESTED`；不回库 |
| 退款单 | `PENDING` | Mock 渠道受理或 `REFUND_PROCESSING` | `PROCESSING` | 售后保持 `REFUND_PROCESSING`，不回库 |
| 售后单 / 退款单 | `REFUND_PROCESSING` / `PENDING` / `PROCESSING` | `REFUND_SUCCEEDED` | `REFUNDED` / `SUCCEEDED` | 支付单和订单为 `REFUNDED`，预占为 `RESTOCKED`，SKU 只回补一次，写入退款与订单 Outbox |
| 售后单 / 退款单 | `REFUND_PROCESSING` / `PENDING` / `PROCESSING` | `REFUND_FAILED` | `REFUND_FAILED` / `FAILED` | 订单和支付仍为 `PAID` / `SUCCEEDED`，预占仍为 `CONFIRMED`，不回库，写入 `REFUND_FAILED` |
| 售后单 / 退款单 | `REFUND_FAILED` / `FAILED` | 商家显式重试 | `REFUND_PROCESSING` / `PENDING` | 使用新的请求幂等键创建新 attempt，复用原退款单业务身份 |

`REQUESTED`、`REFUND_PROCESSING` 和 `REFUND_FAILED` 都必须阻止发货。退款失败后自动恢复发货是不合理的：商家已经批准退款，应由 OWNER 明确重试或后续人工处理，不能让物流与资金处理竞争。

## 回调签名验证接口与 Mock 实现

后端定义渠道边界，具体命名可按现有 Java 风格微调，但职责不得合并：

- `PaymentCallbackSignatureVerifier`：输入 provider、必要 headers 和 raw body，输出验签结果；不修改业务状态。
- `PaymentCallbackParser`：只在验签通过后把渠道报文映射为内部 `ChannelEvent`。
- `ChannelEventService`：处理 Inbox 去重、本地事实匹配、行锁、状态迁移和 Outbox；它是唯一能将渠道回调应用到订单和库存的服务。
- `MockCallbackSignatureVerifier` 使用 `local/test` 配置中的非生产 HMAC 测试值对 raw body 进行确定性验签，用于验证报文被篡改和签名不符时的拒绝路径。这只验证接口边界，不是可上线的安全凭据。

本地 Mock 支付模拟器可继续接收成功、失败或取消选择，但必须先把支付单留在 `PROCESSING`，再异步生成已签名的 Mock 原始回调，通过上述入口完成终态。Mock 退款同理，不得调用领域实体方法直接标记成功。

Mock 异步任务必须是可恢复的耐久化命令，不能只用请求线程中的 `CompletableFuture` 或内存定时器。可复用 `outbox_event` 的状态、重试和领取字段发布 Mock 渠道命令，但命令与对外业务事件的 event type 必须明确区分，且仍需经 Lead 审批契约。

## MySQL 事实边界

- `sales_order`、`payment_order`、`refund_order`、`after_sale_request`、`stock_reservation`、`product_sku` 和 `payment_channel_event` 中已提交的数据是内部业务事实。
- 渠道报文是不可信输入。签名通过只证明报文通过当前适配器验证，仍必须校验 provider、resource ID、金额、币种、资源类型和允许的状态迁移。
- 支付和退款金额、币种一律从已锁定的本地支付单/退款单读取，不使用顾客端或商家端传值。
- SKU 的 `available_stock` 仍是库存事实。支付成功只确认既有预占，不重复扣减；退款成功只将 `CONFIRMED` 预占迁移为 `RESTOCKED` 并回补一次。
- 前端展示、Mock 控制页、Outbox 消费者和日志都不是事实源；页面刷新后必须以查询 API 返回的 MySQL 状态为准。

## 数据库迁移与契约边界

实现阶段只能新增 Flyway 迁移，不得重写 `V1`–`V4`。阶段 2D 的最小 schema 变化包括：

- 新增 `payment_channel_event` Inbox 表和 `(provider, provider_event_id)` 唯一约束。
- 为 `payment_order` 支持 `PROCESSING` 以及必要的最后渠道事件/状态时间；现有 provider 和 provider payment ID 唯一约束保留。
- 把 `refund_order.refunded_at` 改为只在成功时必填；退款单创建时以本地 ID/退款编号作为稳定业务身份，`provider_refund_id` 在渠道受理前允许为 `NULL`，获得后继续受 `(provider, provider_refund_id)` 唯一约束保护。同时增加 `PENDING`、`PROCESSING`、`FAILED` 状态所需的版本、更新时间和有界错误信息。
- 新增退款 attempt 事实或等价的耐久化记录，以 `(refund_order_id, idempotency_key)` 保证显式重试幂等；不得用覆盖原失败记录的方式“重试”。
- 为 `after_sale_request` 支持 `REFUND_PROCESSING` 和 `REFUND_FAILED`，保留原有请求与处理幂等约束。

本文列出的表名、枚举值、回调路径、前端共享类型语义和 Outbox 事件语义是阶段 2D 的默认契约基线。Worker 不得自行改名、删减或扩大；如实现证据表明必须调整，先在交接的 `API_OR_SCHEMA_CHANGES` 中说明影响，由 Lead 批准后再改代码。

## 状态变化与 Outbox 同事务规则

- Inbox 事件从 `RECEIVED` 到 `APPLIED` 的变化、支付/退款/订单/售后/库存的状态变化和对应 Outbox 事件必须在同一 MySQL 事务内提交。
- 任何一个状态变更、库存变更、Inbox 结果或 Outbox 写入失败，整个业务事务回滚；不允许“订单已成功但事件丢失”。
- 必须先以稳定顺序锁定支付单/退款单、订单、预占和按 ID 排序的 SKU，再迁移状态，避免回调、超时任务和退款并发造成锁顺序反转。
- 只有实际发生领域迁移时生成业务 Outbox。完全相同的重放不再生成 Outbox；需人工核查的冲突事件只生成一次 `*_RECONCILIATION_REQUIRED`。
- 调用外部渠道不得占用数据库事务。本阶段的 Mock 渠道命令通过与状态同事务创建的耐久化 Outbox 命令在事务后执行，为未来真实适配器保留同样的失败重试边界。

## API 和前端契约

- 现有订单查询 API 继续作为顾客端和商家端状态查询入口；查询响应增加新支付、退款和售后过渡状态。
- `POST /api/shop/v1/orders/{orderId}/mock-payment` 在 `local/test` 中改为“请求 Mock 渠道异步生成结果”；返回时只能承诺已受理/处理中，不能同步返回支付终态。
- 商家批准退款 API 返回 `REFUND_PROCESSING` 和 `PENDING`/`PROCESSING` 退款单，不得在首次响应中宣称已退款或已回补库存。
- 新增 OWNER 专用的失败退款重试动作，需要新 `Idempotency-Key`；只允许对本商家且处于 `REFUND_FAILED` 的售后执行。
- `packages/domain-types/` 中的 `PaymentStatus`、`RefundSummary`、`AfterSaleStatus` 与后端枚举必须同步更新，不在两个前端分别复制状态字面量。
- 页面不做支付或退款终态的乐观更新。只可以乐观显示“请求已提交”，终态必须来自后续查询响应。

## 顾客端和商家端的异步刷新

本阶段默认使用有界轮询，不引入 SSE 或 WebSocket：

- 顾客端在支付单为 `PENDING` / `PROCESSING` 或售后为 `REFUND_PROCESSING` 时，每 2 秒查询当前订单，最多自动轮询 30 秒。进入终态后立即停止；超时后保留“仍在处理，手动刷新”入口，不把轮询超时显示为支付失败。
- 商家端对 `REFUND_PROCESSING` 订单使用同样的有界轮询；`REFUND_FAILED` 终止自动轮询，显示可审查的失败摘要和 OWNER 重试入口。
- 两个前端在浏览器页签从隐藏恢复、网络重连或用户打开订单面板时立即刷新；组件卸载、登出或进入终态时清理定时器。
- 支付失败/取消或退款成功后，顾客端另外刷新商品和购物车库存；在终态提交前不提前显示库存已释放或已回补。
- 同一页面同一时间只保留一个订单刷新请求，避免旧响应覆盖新状态；页面重载不影响后端处理。

## 前后端工作拆分

### Backend Worker（仅 `apps/backend/`）

- 新增 Flyway 迁移、Inbox 实体/仓库、退款 attempt 记录和必要的版本/唯一约束，不修改已提交迁移。
- 实现回调入口、验签/解析接口、Mock 适配器、Inbox 幂等处理和冲突核查结果。
- 重构支付与退款状态迁移，使取消/超时/支付回调/退款回调共享一致的锁顺序和幂等规则。
- 实现事务后执行的耐久化 Mock 命令、重试边界和 Outbox 事件，禁止在数据库事务内调用渠道。
- 保持订单查询的用户/商家数据隔离，并为新状态增加 H2 集成测试和 MySQL 迁移冒烟。

### Frontend Worker（仅 `apps/shopper-web/`、`apps/merchant-web/` 和 `packages/domain-types/`）

- 先按 Lead 批准的 API/schema 契约更新共享类型，再让两个前端使用同一组状态语义。
- 顾客端把 Mock 支付操作改为“已提交 → 处理中 → 查询终态”，区分支付失败与网络/轮询超时。
- 商家端把批准退款改为处理中反馈，展示失败与 OWNER 重试，并在处理中/失败时禁止发货操作。
- 实现有界轮询、页签恢复刷新、请求竞争避免和定时器清理，不以前端计时器决定支付结果。

### Lead / 集成窗口

- 在 Worker 开工前冻结状态枚举、路径、响应字段、迁移表/列名和 Outbox 事件名。
- 负责 `docs/`、cherry-pick、冲突解决、完整服务启动、跨模块验证和最终 `pnpm verify`。
- 确认后端不信任前端结果，前端不把“已受理”文案写成“已成功”。

### QA（默认不修改受 Git 跟踪的业务代码）

- 根据本文的回调重放、乱序、篡改、超时竞争、退款失败和刷新恢复场景执行验收。
- 分别报告 H2 集成测试、MySQL 迁移冒烟、前端类型/构建和浏览器验收结果，不用静态检查代替真实浏览器证据。

## H2 集成测试要求

后端至少覆盖以下场景：

1. 合法 Mock 支付成功回调将支付改为 `SUCCEEDED`、订单改为 `PAID`、预占改为 `CONFIRMED`，并产生一次对应 Outbox。
2. 同一事件串行和并发重放都只生效一次，Inbox 唯一约束是最终裁决者。
3. 同一事件 ID 搭配不同 payload digest 时不修改支付、订单或库存，并记录冲突。
4. 签名缺失、签名错误、raw body 被篡改、未知 provider 和报文超长均被拒绝。
5. 金额、币种、resource type 或 provider resource ID 不匹配时进入 `REVIEW_REQUIRED`，不应用业务迁移。
6. 支付失败/取消释放预占和 SKU 库存恰好一次；支付成功不第二次扣库存。
7. 超时任务与支付回调竞争时不出现订单、支付、预占和 SKU 相互矛盾；超时后的成功事件进入待核查。
8. 商家批准退款后先得到 `REFUND_PROCESSING` / `PENDING`，在成功回调前订单、支付、预占和 SKU 库存不变。
9. 退款成功回调只回补一次库存；退款失败不回补库存，且待处理/失败售后仍阻止发货。
10. 失败退款可使用新幂等键重试；同一重试键不创建第二个 attempt，也不创建第二张业务退款单。
11. 故意使 Outbox 写入失败时，Inbox `APPLIED` 与所有业务/库存状态一起回滚。
12. 用户和商家仍只能读取各自有权限的订单；回调入口不因免会话认证而跳过验签。

## 真实 MySQL 迁移冒烟要求

H2 的 MySQL 模式不能证明 MySQL 8.4 的 DDL、索引、时间精度、约束和并发行为正确。集成前必须使用 `compose.yml` 的 MySQL 8.4 在空数据库执行冒烟，并保留可重复执行的命令或脚本。验收证据至少包含：

1. 从空 schema 完整执行 `V1`–新迁移，Spring Boot/Flyway 启动成功且 `validate` 无差异。
2. 关键状态列可保存新枚举值，`refund_order.refunded_at` 在待处理/失败时允许为 `NULL`，成功时可写入毫秒精度时间。
3. `(provider, provider_event_id)`、`(provider, provider_payment_id)`、`(provider, provider_refund_id)` 和退款 attempt 幂等约束在 MySQL 上真实拒绝重复值。
4. 在 MySQL 上至少走通一次“创建订单 → Mock 异步支付成功”和一次“批准退款 → Mock 异步退款成功 → 库存回补”，并查询 Inbox、业务表和 Outbox 结果一致。
5. 重复运行应用不重复建表、不重写历史迁移，也不清理现有本地数据伪装成升级成功。

## 浏览器验收场景

1. 顾客创建订单后看到待支付和已预占库存；选择 Mock 支付成功后页面先显示“支付处理中”，不立即显示已支付。
2. Mock 回调到达后，顾客端通过轮询自动变为已支付，支付为 `SUCCEEDED`、预占为 `CONFIRMED`；刷新页面后状态不回退。
3. Mock 支付失败或取消后，顾客端显示正确终态，商品可售库存只恢复一次；重复刷新或重复回调不改变数量。
4. 商家批准未发货退款后，页面先显示退款处理中，订单仍为已支付、库存仍为 `CONFIRMED`，且发货按钮不可用。
5. Mock 退款成功回调后，顾客端和商家端最终都显示已退款，退款单为 `SUCCEEDED`、预占为 `RESTOCKED`，可售库存恢复到支付前数量。
6. Mock 退款失败时，两端不显示已退款，不回补库存；商家 OWNER 可用新幂等键重试，非 OWNER 无权重试。
7. 轮询超过 30 秒仍无终态时，页面显示仍在处理和手动刷新，不显示失败；切换页签再返回或重载页面后可继续从后端恢复状态。
8. 顾客不能读取其他顾客订单，商家不能读取或处理其他商家订单；Mock 控制能力在非本地 profile 不可用。

## 阶段完成标准

- 本文中的 API/schema/Outbox 契约已由 Lead 冻结，Backend 和 Frontend 不存在同名不同义的状态。
- 后端相关 H2 集成测试全部通过，MySQL 8.4 空库迁移和两条异步交易冒烟通过。
- 两个前端类型检查和生产构建通过，浏览器验收覆盖处理中、成功、失败、重试和页面恢复。
- 根目录 `pnpm verify` 通过，且集成窗口确认受 Git 跟踪的变更不包含任何真实支付凭据或本地运行数据。
- 没有接入微信支付、支付宝、Stripe 等真实渠道凭据；Mock 验签值只存在 `local/test` 范围，不被宣称为生产安全实现。
