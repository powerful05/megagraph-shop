# 4D2固定时段测试预约契约 v1（冻结于D1验收后）

基线D1已验收8f1e9f7。新增V12，不改V1–V11；独立appointment_offer、appointment_slot、appointment_purchase、appointment_reservation及必要幂等/锁表。复用SalesOrder、Payment/渠道命令/Inbox、AfterSale/Refund/Outbox、Session/CSRF/CurrentMerchant。新增deliveryType APPOINTMENT，不创建实物SKU/库存/物流，也不创建数字文件权益。每单一个offer与slot、数量1，一种类型；旧实物跨商家购物袋保持。

## Demo业务决策

商家OWNER/EDITOR编辑、确认；OWNER/PUBLISHER发布下架，OWNER显式模拟完成。标题含测试、描述明确不实际预约或线下履约，正价BigDecimal/CNY，所有供给与订单testOnly/testOrder=true。确认与发布核对version，修改资料或新增时段使确认失效、回DRAFT；已购买资料/金额/时段保持快照。

时段为商家专属固定startAt/endAt，UTC Instant事实、前端明确Asia/Shanghai北京时间。创建只接收带offset的ISO时间，拒绝数字时间戳与无时区字符串；解析后先截到毫秒精度，再进行幂等hash、时段校验与TIMESTAMP(3)持久化。拒绝past/start>=end、时长>4小时或开始>30天，截断后不足1ms的区间也明确400拒绝；容量整数token 1–50，独立于实物库存。时段日期/容量创建后不可修改或删除，以避免改动现有预约约定；可显式关闭阻止新购，已预占/已确认订单仍按原约定处理。不支持复杂排班、订阅或部分退款。startAt与expiresAt均为排他边界：now恰好相等也拒绝新购或付款确认。

购买仅当前ACTIVE商家、PUBLISHED且当前确认、OPEN未来slot与剩余容量>0。订单预占HELD，expiresAt=min(now+15分钟,startAt)；支付成功回调在同事务HELD→CONFIRMED，支付成功不是履行完成。失败/渠道取消/主动取消/超时将HELD→RELEASED并释放名额。以服务器now<expiresAt且now<startAt作为确认边界；到界后的成功回调不能预约，记录既有REVIEW_REQUIRED/LATE_PAYMENT_SUCCESS，过期订单与释放事实在同事务保存，不凭渠道occurredAt回填过去名额。

剩余容量=capacity−有效HELD−CONFIRMED−FULFILLED；过期HELD不再占可购容量，但迟到回调也不能确认。当前slot锁读/预约current-read保证最后一名额不超卖，数据库事实决定。关闭时段不使已经合法购买且期限内的HELD失去确认资格；不自动取消已付款预约。

冻结退款规则：CONFIRMED且尚未显式测试完成可申请整单退款，经商家审批和异步Mock退款；申请/处理中/失败均阻止模拟完成，拒绝后恢复。退款成功同事务预约RELEASED（REFUNDED原因）并释放容量。FULFILLED拒绝退款。即使时段经过，未测试完成仍可申请退款；这是Demo规则，不是所有真实服务的政策。履行完成只能OWNER显式调用“模拟测试完成”，允许提前仿真，清楚标明非真实服务，只有PAID/支付SUCCEEDED/CONFIRMED且无阻塞售后/核查时可操作；不能随支付或时间自动完成。

## 共享DTO

AppointmentOfferResponse：id,version,merchantId,merchantName,deliveryType="APPOINTMENT",title,description,price,currency="CNY",status DRAFT|PUBLISHED|OFF_SHELF,confirmedVersion nullable,testOnly=true,deliveryTerms,refundPolicy,slots。Slot：id,offerId,version,startAt,endAt,capacity,remaining,status OPEN|CLOSED。商家读包含过去/关闭时段；消费者只读当前公开且未来可购时段，不公开用户/预约账号资料。

OrderResponse追加appointment nullable，原delivery仅数字、原实物默认PHYSICAL。appointment购买快照：offerId,offerVersion,title,description,unitPrice,currency,slotId,slotVersion,startAt,endAt,capacity,deliveryTerms,refundPolicy,testOnly=true,reservation,completeAvailable（商家当前服务端资格提示）。reservation：id,version,status HELD|CONFIRMED|RELEASED|FULFILLED,heldAt,expiresAt,confirmedAt nullable,releasedAt nullable,releaseReason nullable,fulfilledAt nullable。所有日期序列化ISO Instant；金额服务端确定。

ShopCapabilities追加appointmentDeliveryAvailable。GuideChatResult追加appointmentOffers列表（旧构造/JSON兼容），responseSource新增JAVA_APPOINTMENT；无guide/key仍有统一对话的已确认测试服务检索，明确预约/固定时段/测试服务意图只查当前MySQL最多3个offer（每offer最多10个可购slot）。无供给诚实回复，保留JAVA_DIGITAL/TEXT_MODEL/NO_MODEL路径和连续对话；不能凭全局guideAvailable误标本次回复来源。

## API冻结

- GET/POST `/api/merchant/v1/appointment-offers`；POST带Idempotency-Key与{deliveryType:"APPOINTMENT",title,description,price,currency:"CNY"}。创建DRAFT；canonical payload幂等，同键异载荷冲突。
- PATCH `/{id}` 带{version,title,description,price,currency,deliveryType可选}；POST `/{id}/confirm`、`/publish`、`/off-shelf`带{version}，版本不匹配409。
- POST `/{id}/slots` 带Idempotency-Key及{version,startAt,endAt,capacity}，version为offer当前展示版本；新增slot并使offer回DRAFT/取消确认。POST `/{id}/slots/{slotId}/close`带{version}为slot展示版本，当前version且已CLOSED返回同结果，旧version始终409；返回最新offer。
- GET `/api/shop/v1/appointment-offers` 与`/{id}`，只当前公开资格；无可购时段不伪装支持可购买。
- POST `/api/shop/v1/appointment-orders`，Idempotency-Key与严格{items:[{offerId,slotId,quantity:1,expectedOfferVersion,expectedSlotVersion}]}。根/items字段白名单，整数long溢出/小数/无版本拒绝；混入SKU/数字/客户端价格或多个items明确400/409。同账号同key同payload返回原订单快照，即使offer后来变更；同key不同payload或其他交付类型409。先锁当前offer/slot事实校验两版本与容量，价格/商家/交付状态从服务端取得。返回既有OrderResponse。
- 复用既有消费者订单GET、Mock付款、主动取消、AfterSale及商家审批/拒绝/退款重试API。APPOINTMENT不得走实物发货/收货接口，也不得数字claim/download。
- POST `/api/merchant/v1/orders/{id}/appointment-complete`，Idempotency-Key与{version}（reservation展示版本）。仅所属商家OWNER、Demo启用；order锁内当前资格校验后CONFIRMED→FULFILLED，当前预约version不符409。同商家同key同载荷重放同结果，同key其他订单/载荷冲突，不重复Outbox。前端按钮明确“模拟测试完成”，未付/待售后/已释放拒绝。

## 事务、并发与权限

所有业务与对应Outbox同事务；JDBC/JPA混用必须故障注入实证回滚。现有order相关操作统一先order→payment（如需要）→slot→reservation；不得reservation/slot后反向锁已有order。创建只插入新order，不锁其他已有order。新预约购买幂等使用独立账号mutex表/唯一键；禁止先锁app_user X再slot，因为售后插入需要app_user外键S，可能重演D1隐式FK循环。Backend在实现前核准具体mutex方案并记录，需真实MySQL同键/异键/不同账号容量竞争证明。

当前事实须locking read，不以锁ID后的普通RR旧快照判断价格、容量、售后或履行。超时扫描不能预加载managed旧SalesOrder再拿锁误认为当前；外层scalar ID扫描无业务事务，每ID独立REQUIRES_NEW短事务current lock复查。不跨订单持有slot锁，避免批处理持A/slot再等B与B付款持B等slot成环；定序验证同slot多单付款/超时竞争。容量写变更、取消、退款与完成均锁同slot并读当前reservation；支付前名额不能被付款重复增加。只在Demo/test启用；merchant/账号跨租户404、正常Session写保留CSRF。

购买mutex批准具体方案：appointment_account_lock(user_id PK，FK app_user)，INSERT IGNORE确保行存在，再SELECT自身mutex行FOR UPDATE；保留FK校验且必须取得行才继续。账号外键仅S，不能改为app_user X；旧订单重放先校验交付类型与canonicalhash，不反向锁旧order。H2兼容与真实MySQL同键/不同账号容量需实际证明。

## 前端与验收

商家独立“测试预约”资料/时段工作台，显示北京时间与独立容量；编辑确认发布分开，角色/商家隔离，刷新恢复且不覆盖未保存输入。消费者主入口统一对话卡片→当前offer/slot详情→明确单项试购→Mockpay→确认预约或取消/退款；不清空实物购物袋。未知下单保存原account+key+payload重放，不改键重复买。服务订单显示预占截止/北京时间/确认与显式完成事实，不显示实物库存/数字文件按钮；消费/商家按钮使用服务端资格但最终每次服务端验证。

必做：真实MySQL最后1名额两不同账号并发最多1成功；同key并发唯一/异载荷/跨类型冲突；重复不同事件支付仅一确认；失败/取消/超时释放与晚成功不再预约；当前读RR定序；UTC/+08等价和边界；商家/账号/角色隔离；支付不履行、OWNER显式完成幂等与已完成拒退；未完成退款同事务释放且处理中禁完成；创建/确认/释放/退款/完成Outbox故障回滚；V9→V12与空库迁移；D1/实物回归；浏览器统一对话→最后名额竞争/取消再购/支付/退款/模拟完成/重新打开恢复、桌面390px/键盘/减少动态。未通过前不得声明支持预约。
