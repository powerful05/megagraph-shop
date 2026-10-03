# 阶段4交付记录

状态：4A受控Mock商品生产与4D1自建数字文件已验收；B/C分别完成输入不足裁决；4D2、最终MySQL升级/并发、浏览器与组合验证已验收；阶段4受控Demo已完成，主干/私有远端交付与代码版本CI已通过。当前工作位于隔离集成分支 `codex/phase-4-integration`，阶段3基线 `c7ccb1d`，已快进合入main并正常推送至已实时核准的私有origin/main。恢复先读 `PHASE_4_EXECUTION.md`，再核对实际Git/监听与数据库状态。

## 当前入口与模式

- 商家：`http://localhost:5176/`，图片上传与持久异步任务、产物/尝试历史、授权重试取消、人工编辑确认发布；独立数字文件和固定时段测试预约工作台。
- 消费者：`http://127.0.0.1:3004/`，阶段3统一导购与当前SKU购物袋/跨商家Mock拆单；已验收自建数字文件的独立购买、Mock支付、领取、登录下载和未领取退款；测试预约独立时段试购、预占取消、Mock付款确认和未完成退款。
- 后端：`http://127.0.0.1:8084/`，仅本机监听。当前专属MySQL schema `mg_p4_demo_20261003`，既有Docker MySQL8.4.11实例的3310端口；没有重置原业务库。
- 商家商品生产默认 `mock / filename-draft-v2`，无视觉模型密钥可运行；不声称看图/OCR已验证。4A消费者文本模型曾使用受保护既有配置 `deepseek-flash`，代表性链路新增调用1次，费用未知；D1/D2实际用无guide/key的Java已确认资料检索，当前预览guideAvailable=false，数字与预约能力独立可用，默认无需模型。可以通过受保护配置启用文本模型，但不用于证明视觉/嵌入能力。

## 可复现启动与停止

从集成目录运行 `pnpm install --frozen-lockfile`。默认全Mock启动依次在三个终端执行：

```sh
scripts/phase4-preview.sh backend
scripts/phase4-preview.sh merchant
scripts/phase4-preview.sh shopper
```

默认backend使用单独H2 `var/db/phase4`，属于本地开发模式，不能替代MySQL专项证据。如采用MySQL，将 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 通过受保护环境配置到专用schema后运行同一脚本，勿将凭据写入仓库/命令输出。已有文本导购配置可通过受保护 `APP_GUIDE_CONFIG` 文件启用；未提供则不启用真实文本模型。`PHASE4_TEST_SUPPLY=true` 默认启用标明测试的Demo资料，关闭时不得将这些资料当真实供给。HTTP本地预览使用独立 `PHASE4_SESSION` 与仅本机cookie配置。

本轮实际MySQL启动器 `var/phase4-tooling/run-preview.py` 只在集成目录、已忽略：内部读取现有容器与受保护配置而不打印凭据，不属于交付要求用户依赖的秘密脚本。实跑专项由同目录 `mysql-run.py` 每次创建新的隔离schema，保留日志和数据库，正式验收命令写入执行记录。

停止时在本次终端按Ctrl-C；若终端不可用，先用 `lsof -nP -iTCP:8084 -iTCP:5176 -iTCP:3004 -sTCP:LISTEN` 找PID，再确认 `lsof -a -p <PID> -d cwd -Fn` 属于本次集成目录后仅停止该PID。不能结束旧Demo或按名称批量kill。实际服务是否仍在运行，以实时监听为准，文档或session编号不代替当前进程证据。

## 已通过证据与限制

4A真实MySQL入库专项15/15、V9→V10代表数据升级1/1、根pnpm verify（195总/158执行通过/37条件skip/0失败）分别归档。真实SIGKILL恢复证明QUEUED成功恢复以及RUNNING租约回收后有界死信；没有把超时死信当成功生成。浏览器product68人工确认发布后通过真实文本导购、当前SKU、跨商家Mock一成功一失败；test标识LOCAL_TEST/TEST_CONFIRMED明确。修复测试标识前生成的66/67保持未发布，未追溯修复原产物。390px视口、键盘和减少动态已验收，非实体手机证明。

Session负例证明跨商家任务/产物/媒体404、匿名媒体401、缺CSRF写403。独立维护CLI在专属合成fixture验证24h旧孤立清理及两类引用和新文件保留，不触碰Demo媒体。完整范围及原始日志/截图位置见 `PHASE_4_ACCEPTANCE.md`、`PHASE_4_EXECUTION.md`、`docs/phase4-evaluation/`。

B语义召回与C多跳关系分别裁决为“输入不足，商业准入待验证”。MySQL工程基线与快照已永久归档；未实现Milvus/Neo4j，也未把输入不足算作功能通过。D1真实MySQL22专项、V9→V11升级、根verify和浏览器已通过，范围为单个自建UTF-8文件/支付后24小时；新供给换版不改旧订单文件与金额，未领可退/已领拒退仅Demo规则。4D2真实MySQL22/22、V9→V12升级1/1、最终根283总/202执行通过/81条件skip/0失败错误、浏览器桌面与390px/键盘/减少动态均已通过。main与私有origin/main的首交付SHA ec489168d619bda072b4964ed7b2881e44597fd5相同，GitHub verify push CI通过。真实供给、视觉/嵌入模型、实体设备与真实支付不在已通过声明内。


预约Demo固定规则：每单一个服务/时段、数量1、CNY；容量独立于SKU。预占最长15分钟且不超过时段开始，失败/取消/过期释放；付款只CONFIRMED，OWNER显式“模拟测试完成”才FULFILLED。未完成整单退款释放名额，已完成拒退。混交付类型服务端拒绝，原实物跨商家购物袋与拆单保持。当前测试offer1/v7/29.90，slot3 2026-10-05 10:00–11:00北京时间、容量2；slot1有FULFILLED历史、slot2关闭但原已购order8仍CONFIRMED。时段过后商家正常新建未来测试slot、重新确认发布即可恢复演示，不修改旧订单快照。

当前sameDemo Flyway V12；旧数字订单3/4和原实物订单1/2保留。数字order3领取期限为2026-10-04 04:55北京时间，过期后应正常购买新测试订单，不能延长已购买权益。所有费用/库存/容量/资格/交付由Java和MySQL决定。第三方API本阶段新增1次文本调用，无法获取费用；数字/预约0次模型调用。无视觉/OCR/嵌入真实调用、无购买/升级/重置权益。

最终代码5965869；相应后端专项说明 `apps/backend/INGESTION.md`、`DIGITAL_DELIVERY.md`、`APPOINTMENT_DELIVERY.md` 与 `PHASE_4_ACCEPTANCE.md`。原始日志与截图在集成目录ignored证据，含认证头的原始失败日志不提交/导出；长期非敏感B/C评测摘要在docs/phase4-evaluation。面向用户的交付报告与安全JSON/精选截图将归档本次聊天outputs，Git/远端/CI核实后补最终凭据。不要以文档里旧PID作为结束进程依据。


## 主干与远端最终归档

2026-10-03，主目录 `/Users/qin/Desktop/MegaGraph‑Shop` main由c7ccb1d快进至ec489168d619bda072b4964ed7b2881e44597fd5，原7文件SHA检查在合入前后均一致，原tracked与untracked改动保持，不stash/reset/清理worktree。实时gh确认origin `powerful05/megagraph-shop` 为private/default main；fetch后origin/main9625916是基线祖先，正常git push main成功，无force。远端ls-remote确认ec48916。

代码最终5965869，首交付仅叠加审核文档。GitHub push工作流[verify run37104192321](https://github.com/powerful05/megagraph-shop/actions/runs/37104192321)对ec48916 completed/success，Java21/Node22/pnpm11.19.0、frozen安装与pnpm verify通过。当前归档提交只更新验收/执行/交付文档；最终文档提交SHA、远端一致性和其触发CI最终凭据将记录本次聊天 `outputs/evidence/final-git-ci.json` 与 `outputs/阶段4交付报告.md`，不写自引用SHA。

本次聊天交付目录 `/Users/qin/Documents/Codex/2026-10-03/goal-referenced-pasted-text-files-pasted/outputs`，有10份安全业务JSON、验证汇总、最终Git/CI凭据、15张精选图、详细中文报告和契约/QA文档；SHA256.json供文件核对。原认证日志、私有库、凭据和付费响应原文不导出。原始非公开证据仍在integration ignored路径。必做Demo范围通过；B/C明确输入不足，无Milvus/Neo4j实施，外部真实验证见限制。
