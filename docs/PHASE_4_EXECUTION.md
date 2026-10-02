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

## 环境恢复与已发现问题

主目录原7个文件已完整备份至apps/backend/var/phase4-evidence/baseline-preserved，manifest包含SHA256，binary patch和授权附件保留且忽略。远端fetch成功仍是9625916（阶段2C）。
专用MySQL预览schema mg_p4_demo_20261003，8084；启动器var/phase4-tooling/run-preview.py读取既有受保护文本guide配置与现有容器凭据，不打印/落盘密钥。首次启动失败因supply-expanded profile默认H2 URL覆盖mysql，已显式SPRING_DATASOURCE_URL修复后重启。
MySQL专项运行器var/phase4-tooling/mysql-run.py <MYSQL_PREFIX> <TestClass>每次创建新独立schema，日志在phase4-evidence，不重置任何旧库。

专用MySQL预览已启动8084（baseline c7ccb1d），首次curl受代理返回502，--noproxy '*'后200/65件；数据库URL修正后成功。商家5176、消费者3004 dev预览由Lead启动，session IDs 20291/73079；backend session15924。只结束这些本轮进程，旧Demo保持。
脚本scripts/phase4-preview.sh提供无密钥Mock默认启动以及显式DB变量/受保护导购配置，所有进程仅127.0.0.1监听。最终组合验收后更新入口状态。
B/C离线K5候选20仅评测参数，消费者既有结果上限3/候选50不无依据改动。
