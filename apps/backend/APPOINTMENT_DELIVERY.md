# Phase 4D2固定时段测试预约

只在 `test` 或 `local,supply-demo` 开放。新增V12，旧迁移不改。独立offer/slot/purchase/reservation及账号、商家mutex与完成幂等记录；不创建SKU、实物库存、物流或数字权益。所有资料和订单标记测试，描述明确不实际预约或线下履约。

商家 `/api/merchant/v1/appointment-offers` GET/POST/PATCH，确认/发布/下架分开核对version。OWNER/EDITOR编辑、确认和管理时段，OWNER/PUBLISHER发布/下架，只有OWNER显式“模拟测试完成”。slot创建 `/{id}/slots` 带Idempotency-Key与offer version、ISO offset start/end、capacity，返回最新offer；创建使资料回DRAFT，重新确认。slot关闭使用slot version，旧版本409，当前CLOSED重放不写重复事件。时段固定不编辑/删除，关闭仅防新购买。

slot事实UTC Instant、TIMESTAMP(3)毫秒精度；在DTO构造时先截断至毫秒，再canonical hash、校验和保存，避免相同落库时刻却不同事实。时间必须ISO字符串带明确offset；拒数字epoch、无时区、过去、start>=end、超过4小时、开始超过30天。capacity整数1–50；D2局部严格反序列化，不改变旧DTO输入政策。所有版本需非负long整数token，不截断小数或溢出。

购买 `/api/shop/v1/appointment-orders`，Idempotency-Key与严格 `{items:[{offerId,slotId,quantity:1,expectedOfferVersion,expectedSlotVersion}]}`，根和item白名单，整数token保留BigInteger再longValueExact。创建前独立appointment_account_lock（真实FK app_user）INSERT IGNORE并取得自身行X；显式当前app_user共享读在任何offer/merchant/slot之前，与D1同账号互斥顺序清晰。MySQL父用户/商家使用LOCK IN SHARE MODE；H2按实际database metadata采用FOR UPDATE兼容。appointment_offer完整单表FOR UPDATE，merchant ACTIVE/name另作当前共享读；不通过联表FOR UPDATE隐式拿父merchant X。锁slot再当前锁读该slot全部reservation，容量为capacity−有效HELD−CONFIRMED−FULFILLED；过期HELD不占可购容量。同账号同key同hash重放先于当前offer版本验证，不锁既有order；异载荷或跨交付类型409。

预占期限min(server now+15分钟,startAt)。创建保存金额、offer/version、slot/version/UTC时间/容量及交付退款规则快照。付款成功仅HELD→CONFIRMED，不自动履行；关闭slot不影响已合法预占在期限内确认。server now必须严格早于expiresAt和startAt，迟到成功同事务EXPIRED/RELEASED后REVIEW_REQUIRED，不沿用渠道occurredAt倒填名额。失败/渠道取消/主动取消/超时释放预占；退款成功释放CONFIRMED名额；FULFILLED不可退款。未完成即使时段经过仍可申请退款，这是受控Demo规则。

既有order写路径统一order→payment（如需）→slot→reservation，不在slot后获取旧order。超时外层scalar IDs、不持事务；每ID TransactionTemplate REQUIRES_NEW短事务，当前锁读订单状态/期限，避免跨单持slot再等下一order。付款/退款/释放/完成及对应Outbox同DataSource/JpaTransactionManager事务。

完成 `/api/merchant/v1/orders/{id}/appointment-complete`，Idempotency-Key+reservation version，所属OWNER、PAID/SUCCEEDED/CONFIRMED、无阻塞售后/REVIEW时可显式提前仿真。订单→payment后独立appointment_merchant_lock行X串行商家完成键，再slot/res；父商家仅FK共享锁。跨订单同key异载荷409，重复正确key不重复事件。REQUESTED/REFUND_PROCESSING/REFUND_FAILED阻止完成，拒绝后恢复；退款和完成在同order锁互斥。

OrderResponse新增nullable appointment，数字delivery保持原用途，实物仍PHYSICAL。appointment.completeAvailable是当前服务端提示，每次操作再次当前锁读验证。GuideChatResult新增appointmentOffers，旧构造/JSON兼容，responseSource JAVA_APPOINTMENT；无guide/key统一连续对话可检索当前公开有可购slot的测试预约（最多3个offer，各最多10slot），不调用模型生成交易事实。能力appointmentDeliveryAvailable独立，真实guideAvailable含义不变。

Worker测试 `mvn -s .mvn/settings.xml -f apps/backend/pom.xml -Dtest=AppointmentDeliveryIntegrationTest test`。真实MySQL由Lead使用独立空8.4+schema及MYSQL_APPOINTMENT_URL/USERNAME/PASSWORD运行MySqlAppointmentDeliveryTest（继承全部专项）；V9最新版升级另schema独立运行，追加V12成功及新表为空断言。不要同时构建同一个Maven target。单元/本地测试与真实MySQL、浏览器证据分别记录，不据H2声明预约最终已准入。

Worker最终批次109/109通过，0失败/0跳过：预约20、数字22、实物commerce15、跨商家6、GuideAgent26、GuideChat8、GuideConversation12。预约包括两不同账号最后名额、同key并发、版本与价格快照、严格token/时区/精度、关闭已HELD后付款、重复事件确认、失败取消超时/迟到释放、退款/拒绝/失败资格、显式完成及键、RR定序、各业务Outbox回滚、merchant共享父锁与售后FK定序、逐单expiry对同slot付款定序、stale expiry已支付守卫、已有账号mutex与D1数字同账号真实服务并发、无模型连续对话与历史JSON。跨D1父锁循环此前仅潜在评审，未宣称曾复现；新增显式共享顺序和正常既有mutex测试作为保护。Lead真实MySQL与浏览器尚需验证。
