# 商家异步图片入库 4A

`POST /api/merchant/v1/ingestions` 使用 multipart `image`、可选 `mockScenario` 与 `Idempotency-Key`。返回 HTTP 202 和已经提交的任务，不等待生成。当前 provider 只能是 `mock`；配置其他 `app.ingestion.provider` 会拒绝启动，真实视觉/OCR 尚未验证。

同商家同键有效负载相同会重放已有任务；内容 SHA256、规范文件名、MIME 或情景不同返回 `IDEMPOTENCY_PAYLOAD_CONFLICT`（409）。实际图片经解码且最多 32M 像素；上传最多10MB。PNG/JPEG/WebP均有实际reader，截断伪头不能通过。

读取：`GET /ingestions` 最新100条数组，`GET /ingestions/{id}` 最新任务与产物，`GET /ingestions/{id}/artifacts` 按 attempt 升序返回全部记录，`GET /ingestions/{id}/media` 读取未完成的私有输入。以上路径都位于 `/api/merchant/v1`，继续使用 Session/CurrentMerchant；OWNER/EDITOR可上传、重试和取消，商家成员可读取。

`POST /ingestions/{id}/retry` 与 `/cancel` 接收 `{"version": n}` 并返回完整任务。取消排队、运行或退避任务会栅栏拒绝迟到落库，不能保证取消真实上游；SUCCEEDED不能重试或取消。DEAD_LETTER/FAILED可重试并重置自动次数，累计attempt和历史产物保留；旧同步任务缺少输入文件时明确要求重新上传。

默认自动执行最多3次，失败后2秒/4秒退避，永久解析错误直接DEAD_LETTER；领取60秒租约，provider超时5秒。数据库行锁只用于短事务领取/完成，模型与存储调用在事务外。过期任务在下一次扫描从数据库重领，过期结果即使尚未重领也拒绝。有限线程和有限排队限制卡住Provider造成无限资源增长。

任务有 version/attempt/leaseUntil/nextAttemptAt/errorCode/errorSummary。每次执行的artifact有 inputHash/provider/model/templateVersion/protocolVersion/attempt/elapsedMs/rawOutput/parsedOutput/evidence/unknowns/usage/errorCode。取消或租约过期无法获得实际耗时则elapsedMs为null；Mock无用量费用则usage为null。畸形产物保留安全Mock输出供授权成员检查，普通日志和Outbox不含输入/产物原文。step是当前步骤状态，完整attempt历史以artifacts为准。

成功完成原子保存产物、唯一商品草稿、图片与INGESTION_SUCCEEDED Outbox。任务的创建、领取、失败、取消、重试和租约耗尽均有同事务Outbox。商家仍必须在既有商品领域服务编辑当前版本、确认和发布；AI不生成SKU价格库存、不确认、不发布。成功后不再写回该商品，后续人工修改不会被重跑覆盖。

## 文件补偿与维护

新上传使用唯一storage key；任务创建或Outbox失败、并发重放未使用的本次文件只在DB没有job/image引用时补偿删除。失败删除留待维护，不删除已引用文件。未完成或已取消任务的输入仍属于持久记录，清理不会删除它们。

先构建backend package，再在维护窗口执行 `apps/backend/scripts/cleanup-ingestion-media.sh`。通过受保护环境配置 `MEDIA_CLEANUP_DB_URL`、`MEDIA_CLEANUP_DB_USERNAME`、`MEDIA_CLEANUP_DB_PASSWORD`、`MEDIA_CLEANUP_UPLOAD_DIR`。程序只创建JdbcTemplate和LocalMediaStorage；不启动业务应用、HTTP、Flyway、种子或定时任务，不打印配置或文件key。只清理24小时以前且job/image均未引用的孤立文件；需要维护窗口避免跨进程超长上传与删除竞态。应用内cleanupOrphans另外保留当前进程pendingFiles。

## 验证

H2快速专项：`mvn -s .mvn/settings.xml -f apps/backend/pom.xml -Dtest=IngestionIntegrationTest,ProductFlowIntegrationTest,ProductSupplyIntegrationTest,CommerceFlowIntegrationTest test`。

真实MySQL8.4专项：设置 `MYSQL_INGESTION_URL/USERNAME/PASSWORD` 到专用空schema，再执行 `-Dtest=MySqlIngestionTest`。包含全部入库并发、租约、版本、失败和原子回滚用例；未配置时条件跳过，不能称为MySQL通过。

V9升级保留：设置 `MYSQL_PHASE4_UPGRADE_URL/USERNAME/PASSWORD` 到另一专用空schema，执行 `-Dtest=MySqlPhase4UpgradeTest`。先迁移至V9并插入账号/商家/商品/SKU/订单/支付/会话/Outbox/旧成功入库代表记录，再迁移最新版验证保留；不重置业务库。

日志放在已忽略 `apps/backend/var/phase4-evidence/`。完整浏览器闭环与最终组合验证由集成窗口执行。
