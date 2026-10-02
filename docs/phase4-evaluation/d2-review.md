# 4D2 固定时段测试预约独立审查

状态：合同与旧领域边界已审，新实现与运行验收尚未完成。此文不把 D1 验收、H2 成功或静态守卫当作预约场景已通过。

审查基线：QA worktree `/Users/qin/.codex/worktrees/phase-4d2-qa/MegaGraph‑Shop`，branch `codex/phase-4d2-qa`，HEAD `8e3a733`。已完整读取本目录 AGENTS.md 与 `docs/PHASE_4_D2_CONTRACT.md` v1。QA 仅拥有 `docs/phase4-evaluation` 文档写权限，其余业务实现只读；真实 MySQL、服务、浏览器由 Lead 执行。

## 合同可实施性与本轮澄清

预约独立于数字权益和实物库存：appointment offer/slot/purchase/reservation，加 V12；一个订单一个 offer/slot，数量1，UTC Instant事实、北京时间展示、正价 CNY、测试标识。HELD → CONFIRMED 由合法付款确认，失败/取消/超时 → RELEASED；FULFILLED 仅所属商家 OWNER 显式测试操作。未完成可退，申请/处理中/失败阻止完成，退款成功同事务释放名额，已完成拒退。这些业务规则与现有订单/支付/售后/Outbox可衔接，没有发现必须另造交易链路的缺口。

QA 提出的 close 重放与时间界点歧义已由 Lead 明确：now==startAt 或 now==expiresAt 均拒绝确认；关闭时段要求当前 slot version，already CLOSED 且当前 version 的重放幂等，旧 version 返回409，前端刷新当前资料后操作。

## 已核实旧领域约束与实质风险

旧 `ChannelEventService` 通过 scalar orderId 定位后锁 order → payment；退款回调同样先 order，再 payment/refund/afterSale。`AfterSaleService.request` 锁 order 后创建售后，V4 表对 app_user 和 merchant 都有外键；审批/重试也以 order 优先。预约新增操作不能为账号或商家幂等先锁这些业务父行 X 再等待 slot/order，否则可能与售后插入的外键检查形成循环。购买使用独立 account mutex 已冻结；完成的 merchant 级幂等也需检查相同边界。独立锁表或唯一约束处理均可，但失败必须完整回滚并明确409。

基线 `OrderService.expireOverdueOrders` 在一个 `@Transactional` 中预加载 SalesOrder 列表，再逐个 `findByIdForUpdate`。有两个不同风险：

1. JPA 已 managed 的候选可能在锁查询后仍保持早先状态，不能仅以锁ID宣称当前事实。需 scalar IDs + 当前锁读，并定序覆盖先扫描、后付款、再取得订单锁。
2. 同一事务处理预约 A 后已持有 slot S，再尝试订单 B；另一个付款/取消事务可先持 B 再等 S，形成跨订单循环。scalar IDs 单独不足以修此风险。Lead 已批准外层 scalar IDs 无事务，每 ID 通过独立 REQUIRES_NEW 短事务处理；需真实代理/另 Bean 边界，而不是同 Bean 自调用仅标注注解，并用同 slot 多订单 expiry-vs-pay 定序证明。

现有付款成功仅根据订单状态拒绝 EXPIRED/CANCELLED/PAYMENT_FAILED，没有独立预约 startAt/expiresAt 守卫。D2 到界成功回调即使扫描尚未运行，也必须在同事务把订单过期、HELD释放并标 REVIEW_REQUIRED/LATE_PAYMENT_SUCCESS；不能只标 review 后留下 pending/HELD，不能使用渠道 occurredAt 回填确认。

售后和测试完成互斥需在同一 order 锁内读取当前售后和当前 reservation。前置普通幂等查询可能建立 RR snapshot，因此后续守卫不得沿用普通旧快照。已有 order → payment → slot → reservation 锁顺序需在确认、释放、退款、完成各路径一致；不得锁 slot/reservation 后反向取已有 order。

## 新实现必须验证的业务不变量

| 范围 | 不变量/实际失败风险 | 必需证据 | 当前状态 |
| --- | --- | --- | --- |
| 最后容量 | 两不同账号同一最后名额最多一个 HELD，独立账号 mutex 不得串行掩盖竞争 | 真实 MySQL 定序/并发，最终订单/预约数量与 remaining | 待实现/执行 |
| 过期与复用 | 已过期 HELD 不占当前容量；新买成功后旧单晚成功不可重新占用 | 过期尚未扫描→新购买→旧支付成功，完整终态 | 待实现/执行 |
| 支付重复 | 不同 eventId 重复成功只一 CONFIRMED 与一确认事件，支付不等于 FULFILLED | Inbox/Outbox与 reservation 计数，重复/并发回调 | 待实现/执行 |
| 释放 | 失败、渠道取消、主动取消、超时仅一次释放；同 slot 多订单扫描无跨订单死锁 | 各终态、expiry-vs-pay 定序、当前容量与 Outbox | 待实现/执行 |
| 退款/完成 | REQUESTED/PROCESSING/FAILED 禁完成；拒绝恢复；FULFILLED拒退；成功退款RELEASED | 两方向定序竞争、refund终态与容量、角色/键冲突 | 待实现/执行 |
| 快照/当前事实 | 锁读价格/版本/时段/容量/售后；资料编辑不能改已购约定 | 先普通RR snapshot后编辑/付款/完成，双expectedVersion与旧单事实 | 待实现/执行 |
| 幂等 | account购买键跨类型双向拒绝；merchant完成键跨订单同key仅一结果 | 同key并发/异载荷/不同订单计数，败方无预约/键/事件残留 | 待实现/执行 |
| 时间与请求 | 带offsetISO、UTC/+08等价事实；排他界点；拒绝无时区/past/非法时长与long小数溢出 | strict根/items白名单、时间边界及服务端now断言 | 待实现/执行 |
| 权限 | OWNER/EDITOR编辑确认、OWNER/PUBLISHER发布、OWNER完成；跨商家/账号404 | 正常Session/CSRF、角色及测试开关专项 | 待实现/执行 |
| 回滚 | 创建、确认、释放、退款、完成的业务与Outbox同事务 | JDBC/JPA故障注入，订单/payment/reservation/key全状态 | 待实现/执行 |
| 导购/前端 | 无key真资料JAVA_APPOINTMENT；每次reply标来源；未知下单原payload恢复 | 历史JSON兼容、unknownpending/account fence、实际统一入口 | 待实现/执行 |
| 回归/迁移 | V12仅新增，旧V9代表数据保留，D1与实物能力保持 | 空库/V9→V12真实MySQL与相称根verify | 待实现/执行 |
| 浏览器 | 对话→试购→取消再购/付款/退款/显式完成→重新打开恢复 | Lead真实浏览器、稳定390px/键盘/减少动态图像 | 待实现/执行 |

当前 Backend/Frontend 仍处于新D2基线开工，QA 尚无最终实现提交或 D2 测试执行证据。本轮风险已发 Lead 和两位实现者；只有新的源码、实际报告或状态变化才继续评审，不无变化忙轮询，不重复 D1 已充分通过检查。
