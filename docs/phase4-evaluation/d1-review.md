# 4D1 数字文件纵切片独立审查

状态：冻结合同已读，旧链路风险准备完成；新实现与验收尚未得出结论。

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

## 结论

尚不能宣布 D1 通过。当前产出为旧领域边界核实与具体审查入口；待 Backend/Frontend 的实现与运行证据到位后更新。本次准备不运行付费模型、修改业务或重复充分完成的 4A/B/C 验证。
