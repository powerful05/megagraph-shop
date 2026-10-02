# 阶段3 Demo 本地主干集成

2026-10-03，负责人明确授权“把分支提交到main吧”。本轮提交并合入本地main，不推送远端，不清理分支/worktree，不迁移或重置预览运行数据，不进入阶段4。

## 集成来源

- 原main：`594f0a41d07bafe81220fb8cb3d6ea6404339d84`。
- 阶段3分支：`codex/phase-3a-demo-assets`。
- 新收尾提交：`ab3134e`，Complete phase 3 demo guidance, conversations and acceptance；67个文件，包含此前未提交的3B/3C实现、V9、测试、紧凑目录与Demo验收文档，不含真实密钥或运行数据。
- 3A及早期导购的既有阶段提交随同合入，保留分支历史；使用非快进合并记录两条历史。

原main上的`594f0a4`与分支早期`e226774`是内容等价的后端提交（`git diff 594f0a4 e226774 -- apps/backend`为空）。Git把6个后续演进文件判为add/add冲突：GuideAgent、GuideModel、SpringAiGuideModel、application-guide.yml及两个对应测试。冲突按已验收分支的当前完整文件解决，未退回旧模型协议或重试策略。

解决后暂存的`apps/`、`packages/`、`pnpm-lock.yaml`、`.mvn/`与`ab3134e`逐文件一致。文档新增本集成记录和当前状态提示；10月2日文档中的“未合入”是当时的历史事实，当前集成状态以本文件和main Git历史为准。

## 集成验证

在主目录实际执行`pnpm verify`，2026-10-03 00:13:50 +08:00退出0：后端163项中142通过、21 MySQL专项跳过，0失败/错误；双前端类型检查和生产构建、后端package成功。原日志`/tmp/megagraph-phase3-main-integration-verify-20261003.log`。

业务实现未再更改，因此不重复支付49/14模型评测或重建MySQL测试库；沿用[Demo交付记录](PHASE_3_DEMO_DELIVERY.md)中该实现的真实模型49/49、14/14和独立MySQL21项证据，明确不是本次常规命令执行了跳过项。原浏览器、运行数据与独立工作目录保留。

精确密钥扫描、冲突标记检查、`git diff --check`及代码与来源分支一致性检查通过。密钥、Cookie、数据库、日志、构建产物均未暂存或提交；运行服务仍从原Demo worktree启动，不因合入main自动迁移密钥或数据库。

## 原有主目录修改

合并前将原有已跟踪/未跟踪修改完整保存在Git stash `62022b6f4a5a887194b3a453cb47a848d4e05f27`，名称`preserve-main-before-phase3-integration-20261003`，保留备份而不drop/pop。

AGENTS.md、README.md及两张`docs/design/`图片恢复原内容，不纳入阶段提交。原架构说明的数字权益/服务容量、交付/售后事实与4D逐场景扩展边界合并回工作目录，保持未提交；不恢复已被最新Demo范围取代的“只做女装/等待真实供给”状态。原架构整份原稿仍可由上述stash恢复。

原未跟踪`docs/PHASE_3A.md`是9月30日草案，与已验收3A文档同名；原内容完整另存为`docs/PHASE_3A_DRAFT_20260930.md`，保持未跟踪。正式`PHASE_3A.md`使用已验收版本，不把旧“未开始实现”草案覆盖回去。原设计图片和文档原稿的恢复通过Git blob一致性核对，不丢失。

主目录因此仍有这些保留的未提交文档/设计资料，不以“工作目录全干净”作为集成完成声明。分支上的既有AGENTS.md未提交修改也保留，不擅自提交不同窗口的协作规则。

## 交付边界

阶段3 Demo代码已进入本地main；真实业务、生产验收、远端推送和阶段4未执行。三套原Demo入口/报告/截图仍在独立worktree，当前可用性单独以实际运行状态为准，不把Git集成等同于服务已从主目录启动。
