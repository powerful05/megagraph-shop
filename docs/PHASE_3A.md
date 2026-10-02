# 阶段 3A：商品证据与跨商家 Mock 交易

2026-10-02当前口径：3A作为完整阶段3 Demo验收，不再等待真实供给或额外参与者；最终状态和证据见[Demo交付记录](PHASE_3_DEMO_DELIVERY.md)与[总矩阵](PHASE_3_ACCEPTANCE.md)。下文2026-10-01记录是历史基线，不覆盖最新Demo范围；真实供给留到后续正式试用。

2026-10-01 状态：预览分支已实现商品资料契约、公开推荐证据、跨商家购物车与独立试用子订单，浏览器闭环已通过。本轮另在隔离 MySQL 8.4 验证迁移和跨商家交易；真实商家供给尚未提供，不能将测试数据称为真实供给或完整阶段验收。尚未合入主分支或推送。

长期产品范围包括多品类实物、数字商品和服务。本阶段交易仅实物，当前测试样本已包含服饰、文件袋和纸本；数字权益、服务时段与容量、履约和售后另行冻结，不套用实物库存。整个阶段3的当前状态见 [总验收矩阵](PHASE_3_ACCEPTANCE.md)，下文为历史基线。

## 已冻结与实现

- 商品材质、颜色、版型、场景和各 SKU 成衣尺码绑定商家确认与资料版本；修改需重新确认，来源与生成图片明确标注。见 [资料契约](PHASE_3A_SUPPLY.md)。
- 同一消费者购物车可包含多个商家 SKU，服务端按商家生成独立试用子订单。整组创建失败全部回滚；创建成功后支付、库存、取消与 Mock 售后独立处理。见 [拆单契约](PHASE_3A_CHECKOUT.md)。
- 试用订单不允许发货或计入真实成交统计；不接真实收款、结算或退款。
- 商品价格、库存、发布、订单与支付由业务服务和数据库决定，模型不决定事实；交易变化与 Outbox 同事务。

## 本轮 MySQL 证据

使用新建本地 Docker 容器 `megagraph-3a-qualification-20261001`，MySQL 8.4.11，仅绑定 `127.0.0.1:3310`。原 `megagraphshop-mysql-1` 容器及其数据没有启动、修改或删除。新建四个专用空库：`mg3a_migration_20261001`、`mg3a_upgrade_20261001`、`mg3a_commerce_20261001`、`mg3a_cross_20261001`，没有重置任何已有 schema。

`MySqlMigrationSmokeTest`、`MySqlUpgradeSmokeTest`、`MySqlCommerceSmokeTest` 和 `MySqlCrossMerchantCheckoutTest` 共 7 项，全部通过、无跳过：

- V1–V8 空库迁移与 Flyway validate、新资料和下单组列及唯一约束；
- V5 升级到 V8，保留旧用户、订单、Inbox 和 Outbox；
- Session 交易、异步 Mock 支付、库存确认和过期会话清理；
- 两家同车拆单、重复提交与异址幂等冲突、跨用户隔离、资料快照；
- 并发同键只创建一组、库存只预占一次；
- 第二家库存不足时整组、首家库存和创建事件回滚；
- 一家成功另一家失败，库存独立确认/释放，试用发货被阻止。

新增 MySQL 跨商家测试继承同一组 H2 测试场景，不另写不同语义的重复用例。运行前必须给 `MYSQL_CROSS_URL` 指定独立空库，并配置 `MYSQL_CROSS_USERNAME`、`MYSQL_CROSS_PASSWORD`，使用 `mvn -s .mvn/settings.xml -f apps/backend/pom.xml -Dtest=MySqlCrossMerchantCheckoutTest test`。测试库数据保留供检查。

Flyway 仍给出 MySQL 8.4 高于其已测版本 8.1 的警告，实测迁移已通过；本轮没有为消除提示升级依赖。

## 后续边界

可以继续推进 [3B 导购纵切片](PHASE_3B.md)，使用明确标注的测试供给开发和评测。完整真实试用仍需真实商家资料、用户反馈和主分支集成交付。MySQL 专项通过不等于预览服务已经改用 MySQL；目前页面预览继续使用独立本地 H2 数据。
