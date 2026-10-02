# 4A集成实现独立复核（2026-10-03）

审查基线：集成worktree `codex/phase-4-integration`，HEAD `1f92353551e9dbe6a8342cee0741ff015bcf1f82`。QA现有独立worktree仍在`codex/phase-4-qa`，只读取该集成实现/既有证据，没有切分支、集成、启动服务、调用模型或修改业务代码。本轮不实施4D、不重复B/C或已通过检查。

**结论：本次静态审查未发现任务恢复、取消迟到结果、商家归属、Outbox原子提交或新草稿测试标识的未解决实质漏洞。独立MySQL专项与实际进程崩溃恢复已有正向证据。4A整体Demo仍以Lead完成当前浏览器刷新/取消/商家到消费者Mock交易矩阵为准，不能仅凭本文标记整个阶段完成。** 本文接续[前次4A审查](4A_REVIEW.md)，旧问题表为历史发现，当前关闭状态以下文为准。

## 核心不变量与实现证据

| 不变量 | 当前实现/测试所见 | 结论及边界 |
| --- | --- | --- |
| 崩溃后由DB恢复 | claim查询QUEUED、到期RETRY_WAIT、过期RUNNING，锁行后再次检查；attempt/retry_count/version/lease持久。不会因丢失内存future丢任务 | 静态满足；真实SIGKILL恢复证据见下表，非同进程claim测试替代 |
| 租约/取消拒绝迟到 | complete/fail与cancel都锁同任务；valid要求商家、RUNNING、同attempt、租约未过期、product_id为空。cancel增version/清lease并记录attempt诊断 | 已有MySQL旧attempt、到期未重领、cancel后的complete/fail拒绝用例。取消不能保证真实上游调用撤销 |
| 至多一份有效草稿 | 短完成事务保存product/image/artifact/job/Outbox；成功job不能retry，valid要求product_id为空，V10 product_id唯一及(job_id,attempt)产物唯一 | 并发领取与重复complete验证一次生效；不声称跨系统恰好一次 |
| 商家与角色隔离 | 所有API经CurrentMerchant校验Session账号/显式X-Merchant-Id/成员/角色；读取owned merchant，retry/cancel OWNER/EDITOR；artifact/media绑定job merchant | 商家领域读取拒绝跨店、VIEWER读允许/写拒绝已有专项。专项使用test旧Basic/默认商家上下文，不能把它冒称新接口完整Session/CSRF浏览器测试；现有Session/CSRF测试和静态过滤规则另行支撑 |
| Outbox同事务 | QUEUED/RUNNING/RETRY_WAIT/DEAD_LETTER/retry/cancel/success与状态在同TransactionTemplate内保存对应Outbox；模型/文件存储不在该事务里 | 领取/取消/创建/完成/租约耗尽均有故障注入回滚断言；产品/artifact/成功状态不会在Outbox失败后留下 |
| 输入文件归属与补偿 | 本次UUID上传，失败/并发败者仅删除无job/image引用的本次key。Worker读取文件后比对输入SHA256，改变时INPUT_MEDIA_CHANGED不采用 | 并发只留下胜者一文件，胜者字节一致；同filename不同真实PNG冲突；跨商家同键独立。原INPUT_MEDIA_CHANGED新增用例是再跑MySQL15项的实际理由 |
| 孤立维护无业务副作用 | CLI独立DriverManagerDataSource/JdbcTemplate/LocalMediaStorage，不建Spring Boot、seed、HTTP、Flyway或scheduler；≥24h且无job/image引用才清理 | 应用内cleanupOrphans已测引用/宽限；独立CLI代码路径已审查，但未见该CLI实际运行证据。维护窗口约束保留，多实例/超长上传不由内存pendingFiles解决 |
| 未发布/测试供给边界 | 完成仅新建READY_FOR_CONFIRMATION+UNPUBLISHED，无SKU、确认日期为空。local&supply-demo自动markTestSupply；显式test-supply=true只local/test可用 | H2测试标识用例1/1。ProductEvidenceMapper生成LOCAL_TEST/TEST_CONFIRMED；TestSupplyPolicy仅local&supply-demo允许测试商品公开。新flag不回溯此前错误标识的旧草稿 |
| 并发旧版本/人工编辑 | 成功job不能retry；完成永远创建新草稿且只接受未绑定product的有效claim，之后不再AI写回；现有ProductService按产品version编辑/确认/发布 | 不覆盖人工修订；商家保存/确认/发布使用既有领域流程，浏览器最终闭环另归档 |
| Scheduler不阻塞交易 | scheduled只向专用单线程scans派发；scanning原子标志阻止无限排队，Provider线程数/队列有界，timeout正值与provider=mock校验 | 共享Spring定时线程不等待AI；线程中断不可保证上游真实取消。真实视觉Provider仍未准入 |

相对首次审查，并发媒体文件计数/胜者内容、同名不同内容冲突、跨商家同键断言已补齐；恢复单测已改名`expiredLeaseReclaimFencesLateAttemptUsingDatabaseState`，不再冒称进程重启。升级种子SKU状态已为合法`ENABLED`。V1–V9与阶段3基线diff为空，只有新增V10；没有重写旧迁移。

## 已读取的运行证据

路径以下均相对**Lead集成worktree** `apps/backend/var/phase4-evidence/`（Git忽略）。QA读取指定安全业务证据与测试摘要，不读取配置凭据；计数为Lead执行并由QA核对，不是QA重新运行。

| 证据 | 实际结果 | 范围 |
| --- | --- | --- |
| `mg_p4_ingestion_20261002_170208_010053.log` | 空MySQL8.4独立schema V1–V10成功，14/14通过、0失败/错误/跳过 | 首版4A专项，历史保留，不抹去 |
| `mg_p4_ingestion_20261002_170632_272967.log` | 另一个空MySQL8.4 schema V1–V10，15/15通过、0失败/错误/跳过 | 增加输入媒体哈希改变不采用用例后的专项；测试标识后补的H2用例另列 |
| `mg_p4_phase4_upgrade_20261002_170417_661756.log` | 空schema先V1–V9，写入代表性账号/商家/商品/SKU/订单/支付/会话/Outbox/旧成功任务，再V10升级与validate，1/1通过 | 代表记录保留，不是任意生产数据升级保证 |
| `4a-integration-verify.log` | 195项、0失败/错误、37跳过；实际执行158项通过，前端构建/后端package成功 | H2入库15项、H2测试标识1项包含其中。独立MySQL15+升级1不能写成该命令执行了37项skip；UsernameMigrationPreflight预期失败日志非集合失败 |
| `4a-api-failure-retry.json` | retryable任务POST202约124ms，QUEUED→attempt2成功，失败和成功history保留；fail任务POST202约115ms，自动3次→DEAD_LETTER无product | 当前本机Mock样本，提交未等AI，不是通用延迟承诺 |
| `4a-real-crash.json` | 记录Lead拥有的8084后端PID/cwd与SIGKILL前后任务/产物；见下文 | 同MySQL/schema实际进程恢复，旧Demo没有作为此处终止目标 |

崩溃前job5为RUNNING、attempt1、productId=null、租约至2026-10-03 01:16:41 +08:00；job6为QUEUED、attempt0。`afterRestart`显示job5等租约到期回收，最终DEAD_LETTER、attempt3、无product，完整产物错误顺序为`LEASE_EXPIRED / PROVIDER_TIMEOUT / PROVIDER_TIMEOUT`，未知第一轮elapsedMs=null；job6在重启后SUCCEEDED、attempt1、product69，产物一条。**这证明持久排队能恢复、在途任务不会永久卡RUNNING并保留有界失败历史；job5不是恢复后生成成功的例子，不能把死信写成AI成功。** 成功任务恢复、租约迟到栅栏和原子回滚分别由上述实际进程结果与MySQL专项支撑，不合成不存在的单次场景。

Lead另报告浏览器task4/product68已手工填写59 CNY/库存4的完整资料，save→confirm→publish；task3人工retry累计attempt3→5后cancel并保留history，刷新选择仍在验收。本文只将其记为Lead进行中的浏览器报告，未把截图标题或报告当完整消费者导购/Mock交易验收。已提醒Lead：修复testSupply之前的#66/#67不被自动回溯标记，应保持旧测试草稿未发布并如实说明，不能把它们当真实MERCHANT测试供给。

## 4A剩余验收边界

没有新静态实质阻塞。Lead仍须完成并归档其当前浏览器刷新恢复、取消/重试状态、私有媒体、商家编辑确认发布后消费者当前SKU与Mock购买、小屏/键盘/减少动态结果。已有专项与真实进程证据无需重复；只有新改动/失败才补受影响范围。独立CLI未运行、合法WebP/JPEG具体上传样本、CSRF/跨商家新接口HTTP负例若没有新证据，应按证据范围单列，不将实际PNG样本或共享旧Basic专项扩称任意格式/浏览器安全已实测。

## 可复用的4D验收设计建议（未启动）

- 延续现有OutboxFaultInjector：支付确认/权益发放/预约确认/退款释放对应事件保存失败，领域状态和结果关联必须全回滚；业务层幂等与数据库唯一约束同时验证，不只看重复HTTP成功。
- 下载鉴权在服务端按订单所属账号、已支付状态、领取/退款状态、文件版本与期限回查。复用现有账号/商家隔离方法，同时保留Session/CSRF正常路径，明确不能用test旧Basic覆盖其全部安全结论。
- 数字文件快照是购买时的文件版本/哈希/约定；测试领取/重复支付只发一权益，文件更新不改变旧购买约定，退款与已领取关系由冻结Demo策略决定。
- 预约时段/容量独立表，真实MySQL并发最后名额与取消/超时/支付失败释放；支付回调与取消竞态必须锁同资源或用条件更新，终态重复事件不重复占用/释放，付款成功不自动表示已履行。
- 将4A实际崩溃证据方式复用到需要异步执行的交付任务：同独立schema保留进程前/后状态、唯一结果、attempt与Outbox；没有异步交付任务则不为编号额外制造重启测试。
- 保持显式LOCAL_TEST与公开资格边界：新交付类型只有资料、实时资格和购买/交付/售后闭环通过才对导购开放；每商家子订单一种类型的拒绝应由服务端测试，不能只靠前端隐藏。

这些是冻结下一纵切片时可用的不变量建议，不代表4D代码、契约或验收已经完成。
