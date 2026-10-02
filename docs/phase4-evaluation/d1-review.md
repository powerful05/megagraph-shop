# 4D1 数字文件纵切片独立审查

状态：真实 MySQL 专项出现领取/退款申请死锁，D1 未验收；等待锁顺序修复及相称复验。此前 H2 通过不能替代本次失败。

审查依据是 Lead integration 的 `docs/PHASE_4_D1_CONTRACT.md` v1 与真实源码。本文区分静态判断、自动化运行证据和 Lead 执行的 MySQL/浏览器证据，不将计划或测试方法名算作验收通过。

## 旧链路约束（已静态核实）

基线 `b53f578`：`ChannelEventService.processPayment` 的锁顺序为 payment → order，成功后在同事务更新支付、订单、库存预留和 Outbox；`processRefund` 为 payment → refund → order → afterSale，成功后同事务更新这些聚合。`AfterSaleService.approve` 为 payment → order → afterSale；`request`、`reject` 以 order 锁串行。`OrderMapper` 分别读取库存、订单行、物流、退款，数字订单必须明确 deliveryType 与 purchase，不能用无库存的实物订单行表达。

claim 应使用 order → entitlement，并普通读取 payment 的当前状态；不要在持有 order 后再获取 payment 写锁。request 在相同 order 写锁内拒绝已领取权益；claim 在此锁内拒绝 REQUESTED、REFUND_PROCESSING、REFUND_FAILED。这样领取和退款申请只有一个可获准，不会同时下载与退款。拒绝售后后可恢复领取，但不能延长 24 小时期限。

`InventoryReservationService` 对无 stock_reservation 的订单为空操作，数字订单应断言根本不新增 SKU、库存预留或物流。现有订单有用户与幂等键唯一约束，数字创建要处理同用户同键与已有实物订单的冲突，不能误返回另一个交付类型。

## 新实现逐项审查与证据要求

| 范围 | 必须确认的行为 | 足以证明的证据 | 当前结果 |
| --- | --- | --- | --- |
| 供给隔离 | OWNER/EDITOR 编辑，发布角色明确；跨商家拒绝 | Controller/权限源码 + Session 测试 | 待实现审查 |
| 公开事实 | 确认版本、正价、商家 ACTIVE、asset 有效；编辑撤销确认 | Guard 源码 + 上下架/编辑测试 | 待实现审查 |
| 创建幂等 | 用户级键与载荷 hash；并发仅一 order/payment/purchase，异载荷明确冲突 | 唯一索引与事务边界 + MySQL 并发测试 | 待实现审查 |
| 快照 | 价格、offer/asset 版本/hash、退款与期限约定冻结；资产归属不可穿透 | purchase 内容 + 改价/换版本后下载旧快照测试 | 待实现审查 |
| 支付权益 | 同回调事务仅一 AVAILABLE；失败/取消/过期不产生 | 回调源码 + 重复/乱序/异常 Outbox 回滚断言 | 待实现审查 |
| 领取退款互斥 | CLAIMED 拒退款；阻塞售后拒领；拒绝后恢复 | 统一 order 锁 + MySQL 竞争，且完整终态断言 | 待实现审查 |
| 下载权限 | 匿名/他人/未领/过期/撤销拒绝；已领所属用户按快照读取 | HTTP Session 测试 + header/bytes/hash 断言 | 待实现审查 |
| 回滚 | 创建/领取/支付/退款事件写失败不留下业务半态 | fault injection + DB 状态及 Outbox 计数断言 | 待实现审查 |
| 导购事实 | 仅当前公开数字 offer；数字意图没有供给不捏造；旧历史兼容 | Java 当前重查源码 + GuideChatResult 历史读取测试 | 待实现审查 |
| 浏览器 | 对话卡片→详情→显式购买→Mock支付→领取→下载/刷新；待退款禁领 | Lead 真实 MySQL 浏览器记录/截图/下载 hash | 待 Lead 验证 |
| 实物回归 | 原购物袋、跨商家拆单、支付/退款不变 | 相称现有自动化与真实浏览器 | 待 Lead 验证 |

## 首轮代码审查反馈（正在实现，尚未定最终缺陷）

- MySQL REPEATABLE READ：购买先普通查询幂等订单，再锁 offer id，然后普通查询公开资料，可能沿用首次普通读的旧快照。锁查询必须直接取得当前 version/price/可售事实，并验证必填 `expectedOfferVersion`；同键重放仍应返回先前购买快照。缺失版本与并发改价窗口需要独立断言。
- 同源风险出现在旧 `AfterSaleService.request`：锁 order 前普通查询售后幂等，等待 claim 提交后，普通 entitlement 读仍可能见旧 AVAILABLE。写守卫应在已经持有 order 后使用 entitlement current-read，例如 `FOR UPDATE`，从而拒绝真正已 CLAIMED 的权益。竞争测试应定序到旧快照窗口。
- offer 创建载荷 hash 使用换行拼接字段有歧义，字段内含换行可让不同 title/description 拼出相同字串。使用 canonical JSON 或长度前缀，测试不同字段边界载荷明确冲突。
- 共用订单幂等键需双向保护：数字购买不能重放已有实物订单，旧实物创建也不能返回已有数字订单；后者原来无 deliveryType 检查。
- 首轮前端 PATCH 没有 deliveryType，而 Backend 请求 DTO 原有 `@NotBlank`。双方需要统一 PATCH DTO，避免全部保存请求 400；当前消息已送达两位实现者。

上述反馈已发 Lead 与实现者，最终结果需要检查修订后的代码及运行测试，本文不以中间实现宣判交付失败。

## 修订静态复核

Frontend `aa298a20a920dfbff804e94271bef9798d36ff00`：已核对 PATCH 带 DIGITAL_FILE、购买前当前详情与 price/hash/version 核验、server expectedOfferVersion、账号与商家组件 key 隔离、账号 owner fence、下载会话 abort、安全固定文件名。未知下单结果按账号与 offer 保存原 key/offerId/expectedOfferVersion，重试优先原载荷；明确 4xx 清 pending。数字订单独立展示交付快照与权益，不显示库存或物流按钮；到期、撤销及阻塞售后禁领下载，CLAIMED 禁退款。商家 panel 改为浅字深底。以上是代码事实，尚不能代替真实浏览器视觉与交互证据。

Backend 当前开发树已看到 `currentPublicOffer` 完整 FOR UPDATE 取公开事实，asset_id 单独 FOR UPDATE 取当前关联，写守卫 `currentEntitlement` 使用 FOR UPDATE；载荷 hash 使用字段长度前缀；实物重放拒绝数字类型。旧付款/退款/取消/超时与售后审批路径已改 order 优先；ChannelEvent 定位支付/退款资源改 scalar JPQL，避免先加载会被更新的 managed 实体。仍待最终提交与定序 MySQL 测试证明竞争窗口确已覆盖。

已读取 Backend 首轮 Surefire `DigitalDeliveryIntegrationTest` 报告：10 tests、0 failures、0 errors、0 skipped，12.22 s；其中缺失 expectedOfferVersion、混入 SKU/价格、支付后权益、未领拒下载、实际 bytes SHA-256、private/no-store、匿名/另一账号、未领取退款/已领取拒退、拒绝恢复/过期、创建及领取 Outbox 回滚、旧历史 JSON 有断言。报告对应首轮代码，不宣称之后所有修订已通过，也不是 MySQL 或浏览器验收。支付/退款权益 Outbox 回滚、定序 RR 竞争、不同事件重复支付与商家角色专项仍待最终证据。

Backend `d768099` 最新专项报告为 15 tests、0 failures、0 errors、0 skipped，14.50 s。已读新增断言而非仅方法名：支付 AVAILABLE 与退款 REVOKED Outbox 注入失败后，订单/支付/权益持久状态回滚；不同 eventId 并发支付回调均 APPLIED 且只有一权益；显式先建立普通读 snapshot，再等待商家改价、换资产、确认与发布，购买保存新金额与真实关联资产 hash；显式先建立旧 snapshot，再让 claim 或退款申请先成功，败方两种方向均 Conflict；expectedOfferVersion 超 long/小数拒绝，数字幂等键用于实物创建拒绝。当前看到 assetId current-read、scalar 资源定位和写守卫修订落实，先前反馈没有已知未修的阻断漏洞。

仍需验收证据：上述 15 项在真实 MySQL 执行结果，offer 跨商家及角色写权限，数字支付 FAILED/CANCELLED/EXPIRED 不生权益，实物→数字幂等类型冲突，以及 Lead 真实浏览器对话到领取下载与刷新。双方静态 guard 存在不能替代这些范围的运行证据。

## 结论

尚不能宣布 D1 通过。当前产出为旧领域边界核实与具体审查入口；待 Backend/Frontend 的实现与运行证据到位后更新。本次准备不运行付费模型、修改业务或重复充分完成的 4A/B/C 验证。

## 真实 MySQL 失败与独立锁诊断

Lead 的 `apps/backend/var/phase4-evidence/mg_p4_digital_20261002_204903_225214.log`：`MySqlDigitalDeliveryTest` 共 20 tests、0 failures、1 error、0 skipped，失败方法 `concurrentPurchaseIdempotencyAndClaimRefundSerialize`。日志 SQL Error 1213 / SQLState 40001，领取在取得 `sales_order FOR UPDATE` 时收到 CannotAcquireLockException。原始失败请求 dump 属于受保护中间日志，本文只保存无认证头的诊断事实。

QA 随后只读 `SHOW ENGINE INNODB STATUS`，验证 latest deadlock 仍属于同一 schema，并提取锁类型。没有创建、重置或写入数据库，没有运行 Maven。

| 2026-10-02 20:49:30 UTC，schema mg_p4_digital_20261002_204903_225214 | 已持有 | 等待 | 直接证据 |
| --- | --- | --- | --- |
| 领取 tx 80896 | app_user PRIMARY X record lock | sales_order PRIMARY X record lock，订单 #21 | 当前 SQL `SELECT ... FROM sales_order WHERE id=21 FOR UPDATE` |
| 退款申请 tx 80897 | sales_order PRIMARY X record lock，订单 #21 | app_user PRIMARY S record lock | 当前 SQL `INSERT INTO after_sale_request ... order_id=21,user_id=2` |

InnoDB 明确记录 `WE ROLL BACK TRANSACTION (1)`。这证明互等为 user → order 与 order → user，并非仅凭 Java 可见的 entitlement/payment 顺序推测。源码中 `claim` 显式先锁 app_user；`AfterSaleService.request` 先锁 order 后插入售后。V4 的 `fk_after_sale_user` 要检查 app_user，加入隐式 S record lock；此规则与 [MySQL 8.4 官方锁说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html) 一致。

修复核对不变量：

1. 破除上述循环；如果保留 claim 的账户互斥，所有相关取得已有订单的路径不能 user → order 与 order → user 混用。也可去除此广域账户锁，但要依赖领取键唯一约束并正确处理并发失败，不能丢掉账号级幂等。
2. claim 与退款申请仍在同一个 order 写锁内验证 current entitlement/current售后，成功结果只允许 CLAIMED 且无阻塞售后，或 AVAILABLE 且 REQUESTED；不得同时 CLAIMED 与 REQUESTED。
3. 同账号不同订单使用同一 claim key 只允许一份键记录和相应领取，败方明确冲突并完整回滚；同订单同键重放不重复权益事件或延长 expiresAt。
4. CannotAcquireLockException/Deadlock 不能被改成业务 Conflict 以使既有 XOR 断言表面通过；需要实际无数据库锁错误，并检查最终权益、售后、领取键及 Outbox。
5. Outbox 故障回滚与先建立旧 RR snapshot 的测试仍须通过；不得降隔离级别、关闭外键或删除竞争用例规避本次真实失败。

本节覆盖此前“没有已知未修阻断”的中间判断：目前 D1 有已证实的 MySQL 阻断，Backend 正在修复，尚无修复后运行结论。

QA 随后只读 Backend 当前修复 diff：claim 调整为 order → app_user → 当前权益/支付/售后，再操作领取键；并未删除账户级幂等互斥。新定序 FK 测试先由退款事务持有 order，再等待领取进入 order 锁查询，最后插入售后；旧实现在这一时刻已经持有 user X，可触发原循环，新顺序则没有提前持 user。测试仍只把业务 Conflict 视为败方，不捕获数据库锁错误。同账号两不同订单同 key 竞争断言最终键记录 1、CLAIMED 权益 1。以上说明修复方向与新增覆盖符合不变量；最终提交和真实 MySQL 复验结果仍待 Lead，不以静态 diff 宣称修复通过。
