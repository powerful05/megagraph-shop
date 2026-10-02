# 阶段4交付记录

状态：4A受控Mock商品生产已验收；B/C分别完成输入不足裁决；4D1实施中、4D2尚未开始，整个阶段4未完成。当前工作位于隔离集成分支 `codex/phase-4-integration`，阶段3基线 `c7ccb1d`，尚未合入main或推送。恢复先读 `PHASE_4_EXECUTION.md`，再核对实际Git/监听与数据库状态。

## 当前入口与模式

- 商家：`http://localhost:5176/`，图片上传与持久异步任务、产物/尝试历史、授权重试取消、人工编辑确认发布。
- 消费者：`http://127.0.0.1:3004/`，阶段3统一导购与当前SKU购物袋/跨商家Mock拆单；新增数字功能只有D1组合验收后才声明可用。
- 后端：`http://127.0.0.1:8084/`，仅本机监听。当前专属MySQL schema `mg_p4_demo_20261003`，既有Docker MySQL8.4.11实例的3310端口；没有重置原业务库。
- 商家商品生产默认 `mock / filename-draft-v2`，无视觉模型密钥可运行；不声称看图/OCR已验证。消费者文本模型使用受保护既有配置 `deepseek-flash`，4A代表性链路新增调用1次，费用未知；不用于证明视觉/嵌入能力。

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

B语义召回与C多跳关系分别裁决为“输入不足，商业准入待验证”。MySQL工程基线与快照已永久归档；未实现Milvus/Neo4j，也未把输入不足算作功能通过。4D1、4D2、最终迁移/组合验证、main集成、远端/CI仍须完成后更新本文件。真实供给、视觉/嵌入模型、实体设备与真实支付不在已通过声明内。
