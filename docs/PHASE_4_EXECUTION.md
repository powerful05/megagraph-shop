# 阶段4持续执行记录

## 恢复点 2026-10-03 开工

Goal已建立且active，无数字token预算。基线main c7ccb1dadf055b4c41007343d9c679cecd6381b0。
集成 /Users/qin/.codex/worktrees/phase-4-integration/MegaGraph‑Shop，codex/phase-4-integration。
主目录已有修改：AGENTS.md README.md docs/SYSTEM_ARCHITECTURE.md；未跟踪PHASE_3A_DRAFT_20260930.md PHASE_4_AUTONOMOUS_PROMPT.md docs/design。保留且不提交。
旧Demo在phase-3a-demo-assets，8081/8082/3001/3002/5174运行，独立H2；不停止。现有MySQL容器megagraph-3a-qualification-20261001，8.4，127.0.0.1:3310。新预览拟8084/3004/5176，独立schema/data。
远端powerful05/megagraph-shop，经gh实时确认为private/main；origin/main落后，本轮推送前需fetch重核。
桌面app create_worktree因本聊天projectless返回Not a git repository，已用git worktree add创建隔离工作目录；非系统审批阻塞。
模型能力：已有消费者DeepSeek文本导购，不自动用于视觉/OCR/嵌入；4A先Mock。
证据存放apps/backend/var/phase4-evidence（根var/规则忽略），非敏感评测摘要docs/phase4-evaluation。

## 目录所有权与任务

Lead：docs、根配置、批准契约/迁移编号/集成与完整服务。
Backend Worker：apps/backend，V10保留给4A。
Frontend Worker：apps/merchant-web、apps/shopper-web、packages/domain-types。
QA：只读审查/评测设计，不改业务实现；评测文档临时提交至独立worktree由Lead审核。

当前：4A实现，契约见PHASE_4.md。下一项：领取/完成事务分离与上传异步API，配套工作台。
待做：4A MySQL/浏览器→B/C→4D1→4D2→全验收→本地main→私有远端。

Codex额度由Goal跟踪；第三方AI调用尚无，费用0新增；未知历史费用不编造。不购买或重置额度。
