# 阶段4验收矩阵

| 标准 | 当前结论 | 证据 |
|---|---|---|
| 4A持久异步/幂等/租约/恢复/产物/权限/Outbox | 受控Mock通过 | MySQL15/15、真实SIGKILL恢复、4a-real-crash.json |
| 4A工作台与商家至消费者闭环 | 通过 | job4→product68(v4)→导购→SKU→跨商家Mock拆单；task3重试/取消/刷新/历史 |
| B语义召回准入 | 输入不足，商业准入待验证；未实施Milvus | docs/phase4-evaluation/README.md，固定MySQL3次基线 |
| C关系多跳准入 | 输入不足，商业准入待验证；未实施Neo4j | 同上，无确认关系与多跳对照 |
| 4D1数字购买/领取/下载/退款 | 契约冻结，开始实施 | PHASE_4_D1_CONTRACT.md |
| 4D2预约/容量/取消/退款/履行 | 4D1后执行 | 无 |
| MySQL空库/从V9升级/并发不变量 | 4A通过，最终待4D完成 | MySqlIngestion15/15、Phase4Upgrade1/1，独立8.4.11库 |
| 前端typecheck/build、根pnpm verify | 4A通过，最终待4D完成复验 | 4a-integration-verify.log；195总/158通过/37skip/0失败 |
| 桌面/移动/键盘/减少动态浏览器 | 4A通过，4D待执行 | 桌面与390px截图、Enter/Tab、减少动态；非实体手机验证 |
| 主干集成/远端/CI | 待验收后执行 | 私有远端已确认 |
| 真实视觉/嵌入模型 | 未验证 | 消费者文本模型不证明能力 |
