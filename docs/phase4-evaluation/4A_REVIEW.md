# 4A只读QA审查（2026-10-03）

本文件保留首次未提交实现的历史发现。集成HEAD `1f92353`的关闭状态、独立MySQL与真实进程恢复证据见[集成实现独立复核](4A_REVIEW_INTEGRATED.md)，不要将下方旧问题表当作当前未修复清单。

审查对象：Backend Worker的`codex/phase-4-backend`未提交实现，基线`a0052d3bd89f7b277f7139ac49bb80658de28195`；Worker仍在补测试与修复，本文是代码/契约审查记录，**不是最终4A验收通过**。QA没有修改后端、迁移、测试或启动完整服务，问题已逐项通知Backend和Lead；最终关闭必须依据修复后实际专项证据。

## 现有实现具备的边界

- `IngestionService.create`在图片验证/哈希/存储后用短REQUIRES_NEW事务创建任务+步骤+QUEUED Outbox；同商家键比对input hash、规范filename、MIME、Mock scenario形成的payload hash；并发唯一键冲突后另起事务读取胜者。finally只检查本次UUID key是否有任务/商品图片引用后补偿，不删除胜者媒体。实际DB并发与Outbox回滚仍须测试。
- `claim`锁任务行再次检查状态/到期时间，attempt/retry_count/lease/version以数据库为准。`complete`与`fail`再锁同一行，valid条件包括商家、RUNNING、相同attempt、lease_until>now、product_id为空；取消也持同一行锁。仅状态/attempt匹配不能代替租约未过期检查，当前实现已包括后者。
- Provider调用在Worker线程、数据库事务之外；Future超时只能中止本进程等待并尝试线程中断，不保证真实上游取消。complete在短事务内创建新草稿、图片、不可变attempt artifact与Outbox，迟到complete返回false，成功任务不能retry；产品不是可编辑草稿时反复写回的模型目标。
- 默认Mock不依赖密钥。未知材质/性能/规格明确待确认，商品没有AI价格/库存/自动确认/发布操作。真实Provider/model元信息目前硬编码mock，只能作为本次Mock产物证据，不能宣称真实视觉/OCR能力或通用Provider验收。
- API沿用CurrentMerchant与OWNER/EDITOR写、成员读；任务、历史产物、原输入媒体按merchant归属读取，QUEUED可用`GET /ingestions/{id}/media`，此时无需ProductImage。任务输入媒体响应为private,no-store。

## 已报问题与必须闭环的证据

| 问题/边界 | 初次审查所见 | 建议与最终证据要求 |
| --- | --- | --- |
| 孤立媒体工具附带业务写 | `MediaCleanup`用SpringApplication默认属性禁用seed/worker、server.port=0；默认属性低于local配置/环境，且supply-demo ApplicationRunner按profile直接录入，其他支付/订单调度仍可启动 | 工具只加载窄DB/storage依赖，或明确禁所有初始化/调度且WebApplicationType.NONE。不能只声称默认属性保证无业务写。核实孤立文件删除且有引用/宽限期/路径安全保护，执行工具前后业务数据与端口不变 |
| 旧失败任务无持久输入 | V1–V9 FAILED可能storage_key/input_hash为空，retry目前接受后成为QUEUED，但claim排除storage_key=null | 明确拒绝并提示重新上传，或定义可恢复迁移，不允许永久排队。正常旧SUCCEEDED只保留读取，不能自动重跑 |
| 领取RUNNING的Outbox边界 | claim变更内部状态/租约，无对应STARTED事件；其余QUEUED/retry/cancel/fail/success/lease耗尽已见Outbox | Lead冻结内部领取是否需要对外事件；如果定义事件，与状态同事务。不要为轮询UI假造事件必需性 |
| 取消/过期attempt追溯 | retry保存artifact历史；RUNNING取消没有该attempt产物记录；租约过期artifact elapsedMs写0且无实际Provider计时 | 明确取消记录/未取得产物含义，未知耗时不能被描述为实际模型耗时；不要把生成诊断记录的0ms当Provider调用结果 |
| 图片验真与WebP | 阶段3只有伪头/类型匹配，流未关闭；Backend反馈已加实际解码、32M像素上限、流关闭与TwelveMonkeys WebP reader | 验证截断/伪头图片拒绝、合法JPEG/PNG/WebP接受、解码/像素上限、大小边界，不把新增依赖编译通过当合法WebP实测 |
| 单进程pendingFiles | 补偿/孤立清理以进程内集合保护处理中key，24h宽限减少普通窗口风险，但多实例或超24h上传不是分布式保护 | 本次Demo限定单进程及24h宽限；多实例前应有持久引用/claim或操作协调。不得描述为跨系统同事务 |

提交前再次只读核实已修复：`MediaCleanup`改为独立DriverManagerDataSource/JdbcTemplate/LocalMediaStorage，不创建Spring Boot上下文，不启动HTTP/迁移/种子/调度；只读引用后删除24h宽限外孤立文件，要求维护窗口与受保护环境配置。retry缺storage_key时明确409 `INGESTION_INPUT_MISSING`并提示重新上传；领取增加同事务`INGESTION_RUNNING` Outbox；RUNNING取消保存CANCELLED attempt artifact，租约过期/取消的未知elapsedMs改为null。上述表保留初次发现与需测试项，**代码修复已见，运行专项未由QA执行**，不得读成这些问题仍未改或最终验收通过。

Backend已确认新增WebP reader、实际解码及像素上限、QUEUED媒体路由和不可变历史artifact API；此反馈属于实现说明，最终4A测试结果另由Lead归档。Lead已独立报过lease耗尽缺Outbox，本次读取已见DEAD_LETTER Outbox，不重复提出或冒充QA修复。

## 相称专项矩阵

下面验证业务不变量，优先复用现有测试/故障注入器，不为简单文档新增测试：

| 核心风险 | 必须实际验证 |
| --- | --- |
| 同键并发 | 同商家同键同payload并发只生成一任务/草稿/有效Outbox；败者UUID媒体补偿，胜者文件存在；不同hash/filename/MIME/scenario明确409；同键跨商家独立 |
| 数据持久与重启 | 请求快速返回已提交QUEUED，Worker未启动时可读任务/私有媒体；独立进程重启从数据库恢复，不靠内存future列表 |
| 租约与迟到 | 租约到期但未重领，旧complete也拒绝；重领attempt+1旧complete/fail拒绝；cancel后complete/fail拒绝；第三次租约耗尽持久死信与Outbox |
| 失败与人工retry | timeout/retryable退避与3次上限；malformed永久死信；人工retry次数重置但总attempt/旧产物不丢；旧缺输入任务不可永久排队 |
| 短事务与回滚 | 受控慢Provider期间无业务行锁；完成Outbox注入失败时商品/图片引用/artifact/任务成功全回滚；后续retry只生效一次。create失败仅清理本次未引用媒体 |
| 权限与发布 | MEMBER读取但不可retry/cancel；A读取B任务/产物/输入图拒绝；匿名拒绝；输入媒体不被公开路由越权读取；成功草稿未发布时导购/公开目录不可见 |
| 人工编辑 | SUCCEEDED不能retry；商家编辑仍按product version并需重新确认；尝试旧版本confirm/publish拒绝；过期AI结果不改已确认/发布资料 |
| 清理 | 有引用媒体永不删除，24h内孤立不删，老无引用才删；工具无HTTP服务/seed/Worker/支付/订单副作用；存储key路径边界 |

租约、唯一约束并发、升级保留与短事务回滚应在隔离真实MySQL专项验证；H2测试单列。浏览器必须验证刷新后状态、错误重试/取消、编辑确认发布、当前消费者导购/SKU/Mock交易闭环。代码审查或Green构建不能替代这些证据。
