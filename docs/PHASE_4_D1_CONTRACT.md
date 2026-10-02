# 4D1数字文件纵切片契约 v1（预备冻结，4A+B/C裁决后实施）

复用SalesOrder/PaymentOrder/PaymentAttempt/渠道命令/Inbox/AfterSale/Refund/Outbox/Session；新增独立delivery_offer、digital_asset、delivery_purchase与digital_entitlement，数字供给不创建实物SKU、不写stock_reservation。物理cart/checkout保持。

## 供给与购买约定

商家OWNER/EDITOR创建/编辑数字offer：标题含“测试”，描述说明自建测试文件；价格BigDecimal>0/CNY，交付DIGITAL_FILE，asset归属商家且immutable版本/hash/contentType/bytes。内建UTF-8文本仅用于Demo，不使用他人材料。确认与发布分开且校验当前version；编辑使旧确认失效。OWNER/PUBLISHER发布。只公开PUBLISHED、ACTIVE商家、当前有效文件和正价offer；私有资源不走公共媒体路由。

交付时效为支付成功后可领取，领取期限与下载期限均支付成功后24小时；重领不延长。购买保存offer/version/title/price/CNY/assetVersion/hash/退款规则与交付时限快照，后续改价/替换不影响订单。退款：未领取可申请整单退款，经商家审批/既有异步Mock退款；CLAIMED拒绝，退款申请/处理中/失败待处理阶段均阻止领取。拒绝申请后可恢复领取。此为测试Demo策略，不适用所有真实数字商品。

## API与共享契约（详细DTO由Backend首轮实现后由Lead核准）

- GET /api/merchant/v1/delivery-offers，POST同路径创建{deliveryType:"DIGITAL_FILE",title,description,price,currency:"CNY"}及Idempotency-Key；内建测试文件由服务端赋予商家专属asset，不接收任意文件路径。
- PATCH /delivery-offers/{id}带version及可编辑字段；POST /{id}/confirm、/publish、/off-shelf带{version}。返回offer id/version/merchantId/type/title/description/price/currency/status/confirmedVersion/testOnly/assetVersion/hash/交付约定。每次写变更与对应Outbox同事务。
- GET /api/shop/v1/delivery-offers；GET /{id}，只当前可售公开资料。
- POST /api/shop/v1/delivery-orders带Idempotency-Key与{items:[{offerId,quantity:1}]}。受控一单一个offer/一种类型，数量其他/多个类型/非数字offer/混入skuId明确400或409并提示。服务端确定商家/价格/金额/快照，不接受客户端事实。账号级键+payload hash防并发重复/同键异载荷冲突。
- OrderResponse新增deliveryType（物理默认PHYSICAL）及delivery对象（nullable），包含purchase快照、entitlement（nullable）；复用既有订单/Mock支付/取消/售后API。使用客户端价格/merchantId/状态额外字段须拒绝或明确忽略且事实仅服务端决策。
- POST /api/shop/v1/orders/{id}/digital-claim带Idempotency-Key；锁订单并确认所属账号、PAID且支付SUCCEEDED、无阻塞售后、权益未撤销且未到期；第一次AVAILABLE→CLAIMED写Outbox，重复返回同权益，不重复发放/不延长期限。
- GET /api/shop/v1/orders/{id}/digital-download：所属账号当前已领取且未到期/撤销；每次服务端核验，attachment安全文件名、private,no-store。链接无公共token，另一账号/匿名/退款后拒绝；hash/版本按购买快照读取。

支付成功在相同ChannelEventService短事务中按订单唯一创建AVAILABLE entitlement。重复回调/重复领取不重复发。退款成功同事务REVOKED；claim/refund/售后并发统一订单锁顺序，claim不能穿透待退款状态。取消/失败/过期不产生权益。

## 导购与前端

GuideChatResult新增deliveryOffers列表并兼容既有构造/持久历史JSON；数字意图只取MySQL可售offer并当前重查，标明测试文件与已确认交付约定/未知，无则保持诚实无供给。保留统一对话入口；不将数字文件伪装实物商品卡片/库存。消费者通过对话卡片查看当前offer，显式单项购买，订单里Mock支付/领取/下载/退款；不会自动替换购物袋。商家管理数字offer与退款。轮询有界/刷新恢复/键盘/小屏/减少动态沿用。

## 必做验收

账号/商家隔离；未公开不召回；价格快照/文件版本；幂等创建/并发唯一；重复支付只一份权益；领取/退款竞争；越权与未领取/过期/撤销下载拒绝；未领取退款成功/已领取拒绝；业务/Outbox回滚；实物跨商家回归；MySQL专项和真实浏览器对话→购买→支付→领取→下载/刷新。
