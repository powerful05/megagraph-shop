# 数字测试文件交付（Phase 4D1）

仅 `test` 或 `local,supply-demo` 开放供给写入与购买/领取/下载；`guideAvailable` 继续只说明真实文本模型，独立 `digitalDeliveryAvailable` 表示数字测试交付。全部数字资源保存在独立表，默认自建UTF-8测试资料，不创建商品SKU、不预占或归还实物库存。

商家 `/api/merchant/v1/delivery-offers` GET/POST，POST带 `Idempotency-Key` 与 `{deliveryType:"DIGITAL_FILE",title,description,price,currency:"CNY"}`。标题与描述需标注测试。PATCH `/{id}` 带 `{version,title,description,price,currency}`，生成immutable文件新版本并撤销确认；`/{id}/confirm|publish|off-shelf` POST带 `{version}`。OWNER/EDITOR编辑确认，OWNER/PUBLISHER发布下架，版本冲突409。仅当前确认且公开、ACTIVE商家、有效归属资源与正价CNY对公众可见。

消费者 `/api/shop/v1/delivery-orders` POST带 `Idempotency-Key` 与 `{items:[{offerId,quantity:1,expectedOfferVersion}]}`。仅一项数字文件，不接收SKU、客户端价格或其他事实字段。购买锁当前公开offer，版本不一致409 `OFFER_CHANGED`；同账号同键同载荷重放返回旧订单快照，异载荷或与实物订单交叉使用同键409。保存价格、文件版本/hash、退款规则和24小时时限快照。后续供给更新不影响订单。

复用既有订单、异步Mock付款、取消、售后申请/审批/拒绝/重试退款。付款成功与创建唯一AVAILABLE权益及Outbox同事务；退款成功与REVOKED权益及Outbox同事务。订单统一先锁，再payment/refund/售后/权益；定位回调资源使用scalar查询，避免Hibernate先加载旧实体。JDBC数字表与JPA使用同DataSource和JpaTransactionManager。

`/api/shop/v1/orders/{id}/digital-claim` POST带 `Idempotency-Key`，返回OrderResponse；`digital-download` GET返回authenticated attachment、自建文本安全固定文件名、`Cache-Control: private, no-store`与nosniff。两者每次锁订单核验账号、支付/权益/售后和支付后24小时；重复领取不延长。未领取可申请全额退款，已领取拒绝；售后REQUESTED/REFUND_PROCESSING/REFUND_FAILED阻止领取下载，拒绝售后恢复，退款撤销。链接没有公共token，文件按购买快照/hash读取。

OrderResponse追加deliveryType（旧订单PHYSICAL）与nullable delivery。delivery含purchase字段、nullable entitlement、server claimAvailable/downloadAvailable布尔资格提示；操作时仍再次验证。GuideChatResult追加deliveryOffers与responseSource（TEXT_MODEL/JAVA_DIGITAL/NO_MODEL）；历史JSON缺字段兼容，3/5/6arg构造保留。统一连续对话入口默认可用，数字明确意图由Java召回当前公开offer、不调用模型；未启用模型的其他请求如实返回当前无真实导购。模型Bean仍仅guide profile，不改变已有真实文本模型导购。

验证：`mvn -s .mvn/settings.xml -f apps/backend/pom.xml -Dtest=DigitalDeliveryIntegrationTest test`。MySQL对应继承同专项，使用独立空8.4+库及 `MYSQL_DIGITAL_URL`、`MYSQL_DIGITAL_USERNAME`、`MYSQL_DIGITAL_PASSWORD` 后运行 `-Dtest=MySqlDigitalDeliveryTest`。V9升级保留旧商品/SKU/订单/Session/导购/Outbox且旧订单默认PHYSICAL，新增V11、不可改写旧迁移。浏览器与真实MySQL验收由Lead记录，H2测试不代替该证据。

Worker验证记录：20个DigitalDeliveryIntegrationTest通过、0跳过，覆盖授权/角色、公开与版本、购买幂等与混入拒绝、价格/文件快照、付款/领取/下载与SHA、退款/拒绝/失败阻止领取、到期、失败/取消无权益、跨交付幂等、5种Outbox故障回滚、不同回调ID并发、已建立旧快照的换文件与领取/退款互斥、无模型对话来源及历史JSON。后续改动最终批次66/66通过（数字20、GuideAgent26、GuideChat8、GuideConversation12），此前相称旧commerce/跨商家/guide配置86/86通过（数字18、commerce15、跨商家6、guide26+8+12+1）。这些均本地测试，不声称真实MySQL或浏览器已完成；Lead另归档真实验收。
