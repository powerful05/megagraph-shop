# 阶段4受控Demo契约（2026-10-03）

目标：4A异步商家生产→4B/4C评测裁决→4D1数字文件→4D2固定时段预约→完整MySQL/浏览器验收与主干/私有远端交付，保留阶段3。
依据：负责人本轮附件授权、主目录未提交AGENTS与SYSTEM_ARCHITECTURE，以及已提交PHASE_3_MAIN_INTEGRATION/ACCEPTANCE/DEMO_DELIVERY；后者旧禁止阶段4/集成条款已被本轮授权覆盖。未提交原文件不纳入提交。

## 架构与非目标

MySQL唯一业务事实源；H2只用于快速本地测试。业务状态和Outbox同事务；网络/模型/文件存储在短数据库事务之外。AI不能决定价格、库存、资格、确认或发布。沿用Session/CSRF/CurrentMerchant权限。只新增V10及之后迁移，V1–V9不改。真实收款、生产、发货、订阅、储值、许可证、复杂排班、部分退款不在范围。

## 4A契约 v1

复用ingestion_job/ingestion_step与DraftGenerator，不另造业务任务。POST /api/merchant/v1/ingestions multipart image + Idempotency-Key，返回持久QUEUED任务（202）；同商家同键同内容返回已有任务，同键不同有效载荷409。有效载荷含内容SHA256、规范文件名、MIME与可控Mock情景（默认success）；Mock情景只允许local/test。上传上限现有10MB，验证实际可解析图片，私有媒体按商家隔离；不做真实视觉能力声明。

GET /ingestions 返回最新任务列表；GET /ingestions/{id}读取含steps和artifact的完整状态；POST /ingestions/{id}/retry 与 /cancel 使用版本请求 {version}；OWNER/EDITOR写，成员读取。取消使QUEUED/RUNNING/RETRY_WAIT→CANCELLED并阻止迟到结果，不能撤销已完成草稿或保证上游真实取消。SUCCEEDED不可retry；DEAD_LETTER/FAILED可显式重试，重置自动次数且保留历史attempt。自动最多3次，指数退避，永久解析错误直接死信。

响应兼容既有 id/productId/status/originalFilename/createdAt/finishedAt/steps；新增version/attempt/leaseUntil/nextAttemptAt/errorCode/errorSummary/artifact（nullable）。状态QUEUED→RUNNING→SUCCEEDED，失败→RETRY_WAIT→RUNNING或DEAD_LETTER；CANCELLED终态。步骤含type/status/attempts及安全错误。产物含inputHash/provider/model/templateVersion/protocolVersion/attempt/elapsedMs/rawOutput/parsedOutput/evidence/unknowns/usage（未知费用/置信度null）。敏感原文不进日志/Outbox。

数据库领取锁/租约与attempt栅栏；模型在事务外；完成短事务校验attempt/租约/状态/产品版本，保存产物+唯一草稿+图片+Outbox。成功任务永不自动重跑或覆盖人工编辑。上传失败补偿仅删除本次未被引用的文件，提供有保守宽限期的孤立文件清理入口/脚本。Mock支持success/slow/timeout/malformed/retryable/fail，默认无密钥可用；未知/材质/性能必须明确待确认。

4A验收：并发重复提交/键冲突；重启与租约重领/迟到栅栏；重试耗尽/人工重试/取消；权限/私有媒体；编辑版本冲突/不自动发布；Outbox回滚；商家上传→编辑→确认→发布→导购发现→当前SKU/Mock交易。MySQL专项独立执行。

## B/C评测准入 v1（跑结果前冻结）

固定公开测试资料快照与人工规则标注的开发/保留查询，K=5、候选最多20；关键词/结构化基线沿用GuideCatalogSearch/MySQL公开资格回查。记录Recall@5/NDCG@5、无结果率、最终资格过滤率、p50/p95延迟。离线搜索p95≤200ms，加入外部投影端到端额外预算≤150ms。B在保留集至少Recall提高0.10、NDCG提高0.05且三个独立重复结果一致/无资格回归才工程准入；还须真实可用且版本明确的嵌入Provider。C要求已确认关系和至少三种多跳查询产生相同收益及可核验解释。真实查询缺失则商业准入待验证；不得用Mock语义向量宣称Milvus有效。

分别裁决：已准入并实施验收 / 评测证明暂不引入 / 输入不足，商业准入待验证。无真实嵌入能力与确认关系时后两类不阻塞Demo。

## 4D执行顺序与批准边界

4D1先冻结专用数字offer/文件version/hash/价格CNY/购买快照和权益模型，复用账号/订单/支付事件域服务；只有所属已支付订单领取，领取幂等，服务器下载鉴权与期限，未领取可退、已领取拒绝退款。自建文件明确测试且不进实物库存。
4D1验收后才实现4D2：独立slot与容量/预约reservation，UTC Instant事实与Asia/Shanghai展示，预占过期/支付失败/取消释放，支付确认不代表履约；显式商家测试完成。并发最后名额不超卖。退款取消预约必须同事务。
每商家子订单只含一种类型；服务端拒绝混合交付，保留实物跨商家拆单。每个纵切片实现前更新本契约具体API与状态；未验收类型不宣称已支持。
