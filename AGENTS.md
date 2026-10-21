# MegaGraph Shop 开发约束

## 目标与边界

- 先交付可运行、可测试的业务纵切片，再扩展基础设施或拆分服务。
- MySQL 是商品、SKU、价格、库存、订单和支付状态的唯一事实源；H2 仅用于本地开发和测试。
- AI 只能生成待商家确认的草稿，不能决定价格、库存、发布、订单或支付事实。
- 外部 AI、对象存储和支付渠道必须放在接口后面；本地默认实现必须不依赖真实密钥。
- 业务状态变化与对应 Outbox 事件必须在同一数据库事务内提交。

## 目录所有权

- `apps/backend/`：Spring Boot 业务后端、Flyway 迁移和集成测试。
- `apps/merchant-web/`：商家工作台。
- `apps/shopper-web/`：消费者商城和品牌官网。
- `packages/domain-types/`：前端共享契约；不得复制同名但语义不同的类型。
- `docs/`：阶段范围、默认决策和验收标准。

## 多窗口协作

- 每个 Worker 必须在自己的独立 worktree 和独立分支中工作；不得在共享目录或其他窗口的 worktree 中直接修改。
- Backend Worker 只能修改 `apps/backend/`。
- Frontend Worker 只能修改 `apps/shopper-web/`、`apps/merchant-web/` 和 `packages/domain-types/`。
- Lead 负责 `docs/`、前后端契约审批、cherry-pick 和集成；未经 Lead 确认，Worker 不得自行改变跨端 API 或数据库契约。
- QA 默认只做检查、验收和报告，不修改受 Git 跟踪的业务代码；如需修改，必须由 Lead 明确重新分配目录所有权。
- Worker 不得自行 merge、rebase、cherry-pick、切换分支或清理 worktree；这些集成操作由 Lead 执行。
- 一个窗口已获得的目录所有权，其他窗口不得同时占用或绕过边界修改；需要跨目录时先交给 Lead 重新分工。
- 除非用户或 Lead 明确要求，任何窗口都不得启动子 Agent。
- 只有集成窗口可以启动占用 `8080`、`3000` 或 `5173` 端口的完整服务；Worker 可运行不占用这些端口的构建、类型检查和测试。
- Worker 交接时必须按以下字段完整报告；无变化或无风险时填写 `None`，不得省略字段：

```text
STATUS:
BRANCH:
COMMIT:
CHANGED_FILES:
API_OR_SCHEMA_CHANGES:
TESTS:
KNOWN_RISKS:
INTEGRATION_NOTES:
```

## 改动规则

- 修改数据库结构时只能新增 Flyway 迁移，不能重写已经提交的迁移。
- 金额使用 `BigDecimal` 和明确币种，禁止使用浮点数。
- 当前实物库存只保存在 SKU；任何实物商品总库存都是派生值。未来数字权益数量和预约型服务的档期容量须在独立阶段定义事实模型，不能伪装成实物库存。
- 客户端传入的价格、商家 ID、订单金额和支付结果均不可信，必须由服务端重算或校验。
- 创建订单、支付回调和外部事件必须有幂等键或唯一约束。
- 不把真实密钥、Cookie、密码或本地运行数据提交到仓库。
- 不绕过现有领域服务直接在控制器中修改实体状态。

## 完成标准

- 后端改动至少通过相关集成测试。
- 前端改动至少通过类型检查和生产构建。
- 跨模块或阶段性交付运行根目录 `pnpm verify`。
- 交付时说明范围、验证结果、已知限制和下一步；测试未通过不得称为完成。
