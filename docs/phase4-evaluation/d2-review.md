# 4D2 固定时段测试预约独立审查

状态：D2固定时段测试预约在受控Demo范围通过。mutex修复后的真实MySQL22/22、V9→V12升级1/1、最终根verify（283总、202执行通过、81条件跳过）、正常Session旧事实保留与实际预约浏览器闭环已取得证据；QA只读复核固定日志、JSON、稳定截图及最终源码。首轮两个死锁保留为历史实证，最终结论见“实际浏览器闭环与受控D2验收”节。此结论不等于整个阶段4交付或生产预约上线。

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
| 最后容量 | 两不同账号同一最后名额最多一个 HELD，独立账号 mutex 不得串行掩盖竞争 | 真实 MySQL 定序/并发，最终订单/预约数量与 remaining | MySQL已过；Lead两账号浏览器并发点击同slot，winner HELD、loser无名额，稳定截图已核 |
| 过期与复用 | 已过期 HELD 不占当前容量；新买成功后旧单晚成功不可重新占用 | 过期尚未扫描→新购买→旧支付成功，完整终态 | MySQL过期复用/晚成功终态已过 |
| 支付重复 | 不同 eventId 重复成功只一 CONFIRMED 与一确认事件，支付不等于 FULFILLED | Inbox/Outbox与 reservation 计数，重复/并发回调 | MySQL重复支付/唯一确认已过；浏览器付款仅CONFIRMED，显式操作后FULFILLED已核 |
| 释放 | 失败、渠道取消、主动取消、超时仅一次释放；同 slot 多订单扫描无跨订单死锁 | 各终态、expiry-vs-pay 定序、当前容量与 Outbox | MySQL各释放与逐单expiry定序已过；浏览器主动取消释放后他账号重购已核 |
| 退款/完成 | REQUESTED/PROCESSING/FAILED 禁完成；拒绝恢复；FULFILLED拒退；成功退款RELEASED | 两方向定序竞争、refund终态与容量、角色/键冲突 | MySQL竞争已过；浏览器退款后RELEASED、重购显式FULFILLED，完成后退款409已核 |
| 快照/当前事实 | 锁读价格/版本/时段/容量/售后；资料编辑不能改已购约定 | 先普通RR snapshot后编辑/付款/完成，双expectedVersion与旧单事实 | MySQLRR与快照已过；正常Session现offer v7、旧order7快照v4已核 |
| 幂等 | account购买键跨类型双向拒绝；merchant完成键跨订单同key仅一结果 | 同key并发/异载荷/不同订单计数，败方无预约/键/事件残留 | 首轮mutex死锁已修；修复后22项含原竞争与两个已有mutex测试全过 |
| 时间与请求 | 带offsetISO、UTC/+08等价事实；排他界点；拒绝无时区/past/非法时长与long小数溢出 | strict根/items白名单、时间边界及服务端now断言 | MySQL strict/offset/millis/期限及迟到已过；实际页面UTC事实对应北京时间已核 |
| 权限 | OWNER/EDITOR编辑确认、OWNER/PUBLISHER发布、OWNER完成；跨商家/账号404 | 正常Session/CSRF、角色及测试开关专项 | MySQL角色已过；正常Session跨账号/商家404、匿名401、缺CSRF403已核 |
| 回滚 | 创建、确认、释放、退款、完成的业务与Outbox同事务 | JDBC/JPA故障注入，订单/payment/reservation/key全状态 | MySQL创建/HELD/确认/释放/退款/完成及晚释放故障回滚已过 |
| 导购/前端 | 无key真资料JAVA_APPOINTMENT；每次reply标来源；未知下单原payload恢复 | 历史JSON兼容、unknownpending/account fence、实际统一入口 | MySQL无模型/历史已过；统一对话实际试购与账号恢复已核；unknownpending按源码与专项分列 |
| 回归/迁移 | V12仅新增，旧V9代表数据保留，D1与实物能力保持 | 空库/V9→V12真实MySQL与相称根verify | 空库/V9升级、根verify、sameDemo旧事实摘要及V10–V12 SQL成功记录均已核 |
| 浏览器 | 对话→试购→取消再购/付款/退款/显式完成→重新打开恢复 | Lead真实浏览器、稳定390px/键盘/减少动态图像 | 实际桌面闭环、重开恢复、390px稳定截图、键盘与减少动态已核 |

首轮合同审查时Backend/Frontend尚在新D2基线开工，当时没有最终实现提交或D2运行证据。本轮风险已发Lead和两位实现者；只有新源码、实际报告或状态变化才继续评审，不无变化忙轮询，不重复D1已充分通过检查。最终首轮提交与MySQL失败见下节。

## 首轮开发树 SQL 与输入边界复核

以下是 Backend 未提交开发树的静态证据，不能替代最终 SHA 或运行测试。初轮 QA 文档 `89ec0be` 已由 Lead 集成为 `9376a27`，本节仅补新增发现与修订。

Lead 先发现 `currentOffer` 的 offer join merchant `FOR UPDATE` 可能连带锁 merchant X，使购买 merchant→slot 与售后 slot→插入merchant FK形成循环。QA 随后读到修订：offer 单表 FOR UPDATE 取全部当前事实，merchant 单独在 MySQL 使用 LOCK IN SHARE MODE，H2兼容分支 FOR UPDATE；发布资格也使用此共享 current-read，未退回普通 RR ACTIVE 查询。slot锁内 `used(...,true)` 对预约行 current-read，再计数有效 HELD、CONFIRMED与FULFILLED；已过期HELD不计可用容量。当前预约写路径通过 lockReservation 先 slot 后 reservation，没有看到反向获取已有order的新路径。`appointment_purchase`中的slot/start普通查询读取的是创建后不可修改的快照，不能与可变资格的普通旧读混为一谈。

超时修订已经读到：外层 `expireOverdueOrders` 取消 @Transactional并查询 scalar ID；每ID通过 TransactionTemplate PROPAGATION_REQUIRES_NEW执行 expireOne，真实事务边界不依赖同Bean自调用注解。每单锁当前order/payment，重查状态与expires，再释放预约、订单及支付Outbox。付款回调在预约不能合法确认时，cancel payment、expire order、RELEASED、对应Outbox后才标LATE_PAYMENT_SUCCESS review，扫描前迟到成功不再只留下review。这些是静态已落实，尚待原先提出的定序MySQL测试证明。

QA 新发现的输入风险与后续修订：

- 首轮slot使用普通Integer/Long和Instant DTO，未见全局禁小数转整数配置。Jackson默认允许小数截断为整数，Instant默认也接数字/数字字符串时间戳，不能仅因Java类型是Instant就宣称只接受带offset ISO。此判断对照 [Jackson整数转换官方源码](https://raw.githubusercontent.com/FasterXML/jackson-databind/2.19/src/main/java/com/fasterxml/jackson/databind/DeserializationFeature.java) 与 [InstantDeserializer官方源码](https://raw.githubusercontent.com/FasterXML/jackson-modules-java8/2.19/datetime/src/main/java/com/fasterxml/jackson/datatype/jsr310/deser/InstantDeserializer.java)。购买Map中的Double也可能先把`1.00000000000000001`舍入1.0，再longValueExact通过。已要求实际请求覆盖这些风险，而非只测明显0.5。
- 随后已读 StrictLong/StrictInteger只允许整数token并做exact范围判断；StrictInstant只允许string并用OffsetDateTime.parse；AppointmentOffer/Version/Slot均局部使用strict。StrictAppointmentOrder逐token限制root/items白名单、拒重复字段和非整数token，保留BigInteger原精度，避免先转Double。业务服务仍检查数量1、单items、必填字段与long范围。没有影响旧类型的全局coercion变更。
- 首轮纳秒Instant直接持久TIMESTAMP(3)可能使合法的不足1ms区间落库start==end，触发DATA_CONFLICT/SKU提示409而非明确时段输入错误（全局handler实际处理，未执行API）。当前AppointmentSlotRequest构造器先截到毫秒，再参与hash/时间验证/持久化，小于1ms区间会在业务校验拒绝；UTC/+08等价也规范成同Instant。仍需请求/落库roundtrip与时间边界测试。

新Controller角色静态核对：OWNER/EDITOR创建、编辑、确认、添加/关闭时段；OWNER/PUBLISHER发布下架；OWNER完成。完成先order/payment，再独立merchant mutex，再slot/reservation与当前阻塞售后/核查守卫，同键重放优先原hash。实际merchant/account404、CSRF、相同完成键跨订单竞争、退款与完成互斥仍必须执行，不以Controller角色枚举算验收通过。

当前结论：以上新发现均已告实现者并看到对应修订方向，未发现剩余明确的SQL反锁order路径。该开发树仍未提交、未有D2 H2/真实MySQL执行证据，不能宣称修复实证或D2通过。

## 跨 D1/D2 的父账户锁核查（等待真实定序）

Lead 新提出的潜在路径：D1 digital purchase 显式 app_user X，然后 currentPublicOffer 的 join merchant FOR UPDATE 可能取得 merchant X；D2 若只锁既有独立 account mutex 而未持 parent user S，再 merchant S→slot→创建 SalesOrder FK user S，可能出现 user/merchant互等。QA 已实际读到 D1 的user X及join锁、D2 mutex INSERT IGNORE与SalesOrder两项父外键；目前没有复现此循环，不能把缺少parent S或数据库死锁写作已确认事实。

特别核对 [MySQL 8.4.11 官方 row0ins.cc](https://raw.githubusercontent.com/mysql/mysql-server/mysql-8.4.11/storage/innobase/row/row0ins.cc)：row_ins_clust_index_entry在聚簇索引重复检查之前调用row_ins_check_foreign_constraints；匹配父记录的外键检查取得S/REC_NOT_GAP。另 [MySQL错误处理规则](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html) 说明单statement rollback不释放其locks。这些源码与规则不支持“既有INSERT IGNORE必不持parent S”的确定表述；实际JDBC路径是否已保留该S，应由真实MySQL既有mutex定序/锁证据判断。

Backend 拟在预约取得merchant/slot前显式共享current-read app_user。静态安全理由：D1 user X先取得时，D2在父用户处等待，尚不持merchant/slot；D2 user S先取得时，D1 user X等待，D2后续创建订单对同user的FK S兼容。预约售后持slot后请求user FK仍是S，与提前S兼容，不重演D1原先user X→order / order→user S循环。此理由要求真实MySQL共享锁，不能以FOR UPDATE X替代；H2兼容实现不可算MySQL锁行为实证。

已告Backend的定序要求：用正常业务预先创建并持久化mutex行，同账户/同商家的D1与D2真实service并发，测试调用真实INSERT IGNORE；不得去掉FK或人为绕开mutex模拟假失败。再覆盖预约purchase/售后共享父锁，检查完整订单/预约/支付/Outbox终态，而非只“线程结束”。等待最终代码、提交与MySQL报告后再给运行结论。

## 最终首轮实现与前端静态交接

Backend `1f4af5db994f96824fed3159a362befb9719e446`集成为`3253ad4`，Frontend `19ab0789428e34821fac5c331c137f40b7c49db1`集成为`949ebd4`；旧QA新增`7926d3b`已集成`300a0b3`。QA重新核对首轮最终代码：accountMutex后、offer/merchant/slot前显式currentAccountShared，MySQL LOCK IN SHARE MODE、H2 FOR UPDATE；merchant共享当前读、strict整数/日期、每单独立超时事务与晚成功释放均保留。

新增existingAppointmentMutexSameUserDigitalPurchaseRespectsSharedParentOrdering先正常购买并取消以持久化mutex行，断言行数1，再在真实事务持user X，后台调用真实预约purchase，同事务真实digital purchase完成后预约也完成。此用例证明组合可完成，但不能区分后台等待发生于INSERT IGNORE外键检查还是explicit共享读，不能表述为“此前跨D1循环已经复现并修复”。QA实际读取H2报告：AppointmentDeliveryIntegrationTest 20/0 failures/0 errors/0 skipped，4.008s；XML记录jdbc:h2:mem。数字22/0/0/0报告亦已读。Backend另外报告相称109/109通过；该总数为Worker交接，不代替下节真实MySQL结果。

前端最终源码与API/shared DTO静态核对一致：appointmentOffer/slot/reservation字段、两expectedVersion单item购买、appointment-complete reservation.version/Idempotency-Key、北京时间工具、JAVA_APPOINTMENT与capability。未知购买按account+offer保存原key和完整payload，优先重放；public详情404仍可核对原请求；成功或明确4xx清pending，网络/5xx保留，账号变化有owner fence。HELD到expires/start发一次回读并禁Mock成功；CONFIRMED明确尚未测试完成，商家只有OWNER且server completeAvailable/支付/售后守卫允许显式完成。组件请求有15s限制与释放，预约不清实物购物袋，不显示数字下载/实物库存按钮。当前未发现阻断静态问题。

Frontend交接报告两端typecheck/build通过，US/Shanghai TZ各10测试（3新增、7既有）通过；QA本次没有自行运行这些命令，最终集成构建与浏览器须引用Lead日志/实际记录。桌面/稳定390px、键盘、减少动态、最后名额/取消再购、退款与模拟完成、重新打开恢复仍待真实浏览器证据，不能以此次静态检查替代。

## 首轮真实 MySQL 失败与mutex锁证据

QA独立读取Lead固定日志 `apps/backend/var/phase4-evidence/mg_p4_appointment_20261002_215627_269959.log`：隔离schema同名，真实MySQL8.4、空库V1–V12迁移成功；MySqlAppointmentDeliveryTest 20 tests、0 failures、2 errors、0 skipped，BUILD FAILURE，结束2026-10-02 21:56:47 UTC。两项错误均CannotAcquireLockException/MySQL deadlock，不能因H2通过或failure计数0就宣称专项通过：

- twoAccountsLastCapacityAndSameKeyConcurrent：同账户同key竞争阶段，在 `select user_id from appointment_account_lock where user_id=? for update` 失败。
- completionRefundAndCrossOrderCompletionKeysSerialize：同商家跨订单完成键竞争阶段，在 `select merchant_id from appointment_merchant_lock where merchant_id=? for update` 失败。

QA只读了Lead保存的 `d2-first-mysql-deadlock.txt`，没有执行数据库命令。最新死锁为merchant例：2026-10-02 21:56:44 UTC，事务99009与99010在appointment_merchant_lock PRIMARY记录merchant_id=1各持S record/not-gap，各等待同记录X record/not-gap；InnoDB回滚事务99010。这确证的是mutex本身的S→X升级互等，来源与INSERT IGNORE重复键取得S后再SELECT FOR UPDATE的代码一致。account例的最新dump已被merchant例覆盖，只有实际异常日志；不得编造它的事务号或声称相同记录锁已实测。

本次确证失败与上节未复现的跨D1父表假设分开记录。Backend拟将两个mutex改为no-op INSERT ON DUPLICATE KEY UPDATE，直接取得重复键X，保留FK和显式早期user共享读；不要吞数据库异常为业务Conflict、降隔离或删竞争测试。修订后须真实MySQL保留同账户同键、不同账号最后容量、同商家不同订单完成同键、父FK共享定序与expiry定序，检查完整终态和Outbox；不能仅靠H2重验结束。

首轮失败时Lead记录恢复点`187c056`，D2 V12尚未部署预览，旧Demo未改；当时没有修订后MySQL或升级通过证据。此节只保存脱敏摘要，受保护原始日志与锁dump不进入Git；最新结果见下节。

## mutex修复后MySQL与升级证据

Backend修复`3bb4a68604a719c6b737711ebbaa3d3484833dd5`集成为`5965869`。QA实际读取该diff：仅两个mutex INSERT IGNORE改为no-op ON DUPLICATE KEY UPDATE，保留真实FK、后续FOR UPDATE行存在验证与currentAccountShared；没有异常吞掉/自动重试或降低隔离。原20个业务测试没有删除或削弱，只把JdbcTemplate设为spy并新增2例真实语句等待测试。

新增existingAccountMutexUpsertAcquiresExclusiveLockBeforeFollowupSelect与existingMerchantMutexUpsertSerializesDifferentOrderCompletionWithoutUpgradeDeadlock：用正常业务建立已有mutex行，真实upsert委托返回后第一线程暂持事务，第二语句在200ms观察窗口内尚未返回；释放后同账号同key得到同一订单且DB订单计数1，不同订单同完成key败方Conflict、key记录1且FULFILLED总数1。故障是检查真实数据库操作，没有改FK或人为模拟缺锁。QA读取Worker固定 `apps/backend/var-d2-mutex.log`与报告：H2预约22/0/0/0、BUILD SUCCESS，9.812s；该H2证据仍与下一项MySQL分列。

QA独立读取integration固定日志 `apps/backend/var/phase4-evidence/mg_p4_appointment_20261003_062150_100114.log`：真实MySQL8.4，新空schema V1–V12全迁移成功，MySqlAppointmentDeliveryTest 22 tests/0 failures/0 errors/0 skipped，BUILD SUCCESS，专项18.38s、Maven总22.276s，结束2026-10-03 06:22:14 UTC。原20项同键/跨订单竞争、最后容量、RR、父FK与逐单expiry、业务Outbox回滚，加两个已有mutex测试均执行；首轮确证的mutex死锁阻断已获复验通过。不能把这次通过改写成此前未复现的跨D1假设曾失败。

QA随后读取独立升级日志 `mg_p4_phase4_upgrade_20261003_062240_660481.log`：先迁移V1–V9，加入代表性旧事实，再追加V10–V12；MySqlPhase4UpgradeTest 1 test/0 failures/0 errors/0 skipped，BUILD SUCCESS，2.028s（Maven总3.165s），结束06:22:44 UTC。已读实际断言：旧用户、已发布商品version7、SKU价格/库存、订单/订单行/支付、Session及属性bytes、会话、ingestion历史与Outbox保留，旧订单delivery_type=PHYSICAL，原数字表空，新增7预约表空，V12迁移历史成功。不是只检查表存在。

上一轮准入结论：后端核心与真实MySQL并发/回滚、mutex修复、空库与升级gate已通过；当时最终根verify和正常Session实际预约浏览器仍待。Lead停机后恢复sameDemo并增量V12，最新组合与保留证据见下节；不将运行进程当成页面验收，也未新增模型调用。

## 最终组合验证与sameDemo旧事实保留

前一QA更新`ac853063`已由Lead集成为`bbf4e5a`。QA独立读取固定 `apps/backend/var/phase4-evidence/d2-final-integration-verify.log`：根命令链typecheck → build → test:backend → package:backend；两端typecheck Done与production build Done。后端283 tests、0 failures、0 errors、81 skipped，即202实际执行通过，测试BUILD SUCCESS结束2026-10-03 14:24:46北京时间；package明确-DskipTests，仅打包BUILD SUCCESS结束14:24:49，不能把第二次打包算又执行一轮测试。

本根轮次的81条件跳过含MySQL预约22、数字22、ingestion15、导购会话12、跨商家6，以及4项迁移/升级/commerce smoke；缺环境所以跳过，不是实际MySQL通过。真实预约22、升级1分别引用此前独立schema固定日志。根H2实际预约22、数字22、commerce15、跨商家6等均执行通过；此次最终组合验证已覆盖前端最终构建与原交易回归。

`d2-v11-preservation-session-check.json`由Lead在恢复后的sameDemo `mg_p4_demo_20261003`使用正常Session/CSRF客户端记录，QA只读取摘要，没有重新发送请求。记录oldMigration=11、newMigration=12，旧D1订单#3为PAID、#4为REFUNDED，二者unchanged=true；旧文件读取248 bytes，SHA-256 `3fe446074ea9314be1e9776e88c5eb1fdff616dcd8fce05462d142b6adecaf1a`与D1购买快照和QA此前实际下载文件hash相同。旧实物订单#1 PAID/支付SUCCEEDED，#2 PAYMENT_FAILED/支付FAILED，product#68仍公开。摘要不包含完整前后订单对象，本次只确认这些记录与已归档D1事实一致，不扩展为新的一次逐字段双快照比对。

同摘要capabilities：mockPaymentAvailable=true、guideAvailable=false、demoCatalogAvailable=false、digitalDeliveryAvailable=true、appointmentDeliveryAvailable=true；normalSessionCsrfClient=true。这些说明恢复预览的能力配置与旧事实保留，不替代预约浏览器验收，不把available标志视作全部准入已通过。迁移版本由Lead另作MySQL检查，HTTP摘要明确记录这一来源。

上一轮尚待D2必做gate为Lead实际预约浏览器闭环与相应记录：统一对话/当前slot明确试购、最后名额两账号、取消再购、关闭已HELD后合法付款、退款/显式模拟完成、重新打开恢复，以及稳定桌面/390px、键盘与减少动态。正常Session预约角色/账号拒绝也按实际交互/API证据归档。真实供给、实体手机、真实服务和生产支付未验证，不属于本受控Demo已验证范围。当时D2及整个阶段4尚不能整体完成；最新D2结论见下节。


## 实际浏览器闭环与受控D2验收

前一QA提交`d71cbb40`已由Lead集成为`b927501`。本次QA没有发送业务请求、执行SQL、启动服务或重跑已充分通过测试；只读integration最新代码、三个固定JSON与浏览器截图。浏览器操作、正常Session/CSRF请求和只读SQL由Lead执行，独立静态检查、JSON一致性与图像核对由QA完成。运行数据、截图和敏感原始日志仍放ignored evidence，Git只提交本脱敏记录。

Lead先在商家工作台创建测试offer#1、29.90 CNY、两个future slot各capacity1，并确认发布v4；消费者统一对话取得当前测试资料后明确选择slot1。两个已有账号以CUA Promise.all点击同一最后名额，`d2-last-seat-winner-held.jpg`显示订单#5预占、快照v4、slot1容量1、北京时间10月4日10:30–11:30及15分钟预占截止；`d2-last-seat-loser.jpg`显示“当前时段无可用名额”。loser详情仍显示此前读取的剩余1/1，这是并发后旧展示，没有误创建订单的证据；服务端已拒绝，用户需要重新查询当前名额。截图证明实际界面结果，数据库最多一份、同key唯一与Outbox断言另由真实MySQL22证明，不能仅从Promise.all认定数据库语句精确同时执行。

随后winner主动取消#5释放名额，账号2重购#6，Mock付款仅确认预约；申请退款后商家审批，回调完成后#6 REFUNDED/RELEASED，再重购#7付款仍CONFIRMED，OWNER以Enter显式“模拟测试完成”后才FULFILLED。QA查看`d2-refunded-released.jpg`与`d2-explicit-fulfilled.jpg`，前者明确退款完成、释放原因REFUNDED，后者显示完成时间、非真实服务及Demo不再退款规则。`d2-confirmed-not-fulfilled.jpg`显示付款成功后的未测试完成退款入口；完整确认文案另在最终mobile订单图中可见。

处理中禁完成的证据分列：Lead实际CUA getAX观察到REFUND_PROCESSING与“模拟测试完成”disabled；该动作报告并非QA重新执行。保存截图时异步退款已完成，原`d2-refund-processing-guard.jpg`已由Lead改名为`d2-merchant-refunded-released.jpg`，QA看到的是退款成功/RELEASED终态，不能作为处理中截图。源码商家canCompleteAppointment同时要求OWNER、PAID、payment SUCCEEDED、无核查、CONFIRMED、server completeAvailable且无阻塞售后；后端complete在order→payment→merchant mutex→slot→reservation当前锁读内再次检查阻塞售后。实际MySQL两方向退款/完成定序测试支持互斥，无需为补截图重复造退款。

`d2-session-business-check.json`记录normalSessionCsrf=true、modelCalls=0，QA核对订单事实：#5 CANCELLED/RELEASED原因CANCELLED；#6 REFUNDED/RELEASED原因REFUNDED；#7 PAID/FULFILLED且有confirmedAt与fulfilledAt，三单快照均offerVersion4、slot1、29.90 CNY。另slot2正常创建HELD订单#8后关闭slot，期限内合法Mock付款200，#8 PAID/CONFIRMED；与canConfirm只按已购约定及排他时间守卫、不以当前slot CLOSED撤销已购资格的源码一致。

同一正常Session记录的拒绝：跨账号读订单404、跨商家完成404、匿名订单401、缺CSRF写403、已完成退款409、预约走digital claim404、实物receipt409、混合items400。此处是HTTP实证，不将GUI导航工具拦截当服务端拒绝。角色全集、非法token/边界、同键重放、晚支付、回滚等继续引用MySQL专项，浏览器没有逐项重复这些充分测试。

`d2-demo-migration-sql-check.json`来源为Lead在2026-10-03 06:39 UTC只读SQL，schema mg_p4_demo_20261003、MySQL8.4.11，V10/11/12 success=true。预约计数FULFILLED1、RELEASED2、CONFIRMED1与订单5–8的Session终态一致；字段名reservationStatusCountsAt0621Plus不精确，采样时刻以JSON source为准。QA没有独立连DB；空库及V9代表数据升级仍依据此前固定测试日志。

重开消费者后账号对话与订单恢复，商家身份、offer与slots恢复。QA实看`d2-mobile-restored-orders.jpg`（390×844）、`d2-mobile-published-future-slot.jpg`（390×2268全页）、`d2-mobile-reduced-motion.jpg`（390×844）：订单8确认但未完成文案可读，商家OWNER与v7已发布、已关闭slot2、第三future slot独立容量2均可见，减少动态checkbox已选中。Lead浏览器测得两端innerWidth/scrollWidth均390，并以Enter打开订单/模拟完成；QA核对图片像素和焦点可见，不将像素宽度自行等同重新执行scrollWidth检查，也不声称实体手机验收。

商家新增第三future slot为10月5日北京时间10:00–11:00、capacity2，再确认发布v7。`d2-final-future-slot-snapshot.json`正常Session记录currentOfferVersion7、slot3 OPEN/remaining2、UTC02:00–03:00，与商家截图对应；旧order7仍offerVersion4/FULFILLED，说明供给更新未替换该已购快照。本JSON并未再次附全部旧订单字段，价格/标题/条款旧快照来自前述business JSON和此前MySQL快照断言，不扩张为此次完整前后逐字段比对。

最终只读源码复核仍保持两个no-op upsert mutex、早期user共享读、order优先锁序与独立每单expiry事务；购买重放优先原key/hash，两个expectedVersion与current slot资格校验保留。GuideRequestExecutor预约意图使用当前公开MySQL资料并标JAVA_APPOINTMENT；前端显示每次reply来源，current slot明确选择、账号fence、未知下单原payload恢复、HELD界点回读与禁付款均保留。没有新未修静态阻断。实际“重开恢复”证明已保存会话/订单恢复，不声称本轮浏览器注入过网络5xx未知提交；该路径按源码与专项证据验收。

结论：冻结合同的D2受控固定时段测试预约纵切片可通过，覆盖独立容量、当前价格/版本、唯一购买、支付仅确认、取消/失败/超时释放、晚支付核查、退款与显式完成互斥、正常Session权限、快照保留、Outbox回滚、迁移回归，以及实际统一入口与恢复、稳定390px/键盘/减少动态。范围是项目自建测试供给、Mock支付与明确模拟完成，未提供真实预约或线下服务。真实商家供给、生产支付渠道、真实履约及实体手机未验证且不属于该受控D2范围；整个阶段4的最终集成、main保护审计和交付仍由Lead单独收敛。
