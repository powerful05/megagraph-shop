# 阶段4验收矩阵

| 标准 | 当前结论 | 证据 |
|---|---|---|
| 4A持久异步/幂等/租约/恢复/产物/权限/Outbox | 受控Mock通过 | MySQL15/15、真实SIGKILL恢复、4a-real-crash.json |
| 4A工作台与商家至消费者闭环 | 通过 | job4→product68(v4)→导购→SKU→跨商家Mock拆单；task3重试/取消/刷新/历史 |
| B语义召回准入 | 输入不足，商业准入待验证；未实施Milvus | docs/phase4-evaluation/README.md，固定MySQL3次基线 |
| C关系多跳准入 | 输入不足，商业准入待验证；未实施Neo4j | 同上，无确认关系与多跳对照 |
| 4D1数字购买/领取/下载/退款 | 受控数字文件Demo通过 | MySQL22/22/0skip；offer#1人工确认发布→订单#3实存248bytes原hash；订单#4未领退款REVOKED/下载409；变价换文件后旧单仍原版本 |
| 4D2预约/容量/取消/退款/履行 | 4D1后执行 | 无 |
| MySQL空库/从V9升级/并发不变量 | 4A/D1通过，最终待D2 | Ingestion15/15、Digital22/22、V9→V11 Upgrade1/1，独立8.4.11库 |
| 前端typecheck/build、根pnpm verify | 4A/D1通过，最终待D2 | d1-integration-verify.log：239总/180执行通过/59条件skip/0失败；末次文案前端typecheck/build d1-final-frontend.log通过 |
| 桌面/移动/键盘/减少动态浏览器 | 4A/D1通过，D2待执行 | 桌面与390px截图、数字详情/订单/商家确认Enter、减少动态；非实体手机验证 |
| 主干集成/远端/CI | 待验收后执行 | 私有远端已确认 |
| 真实视觉/嵌入模型 | 未验证 | 消费者文本模型不证明能力 |

## D1证据与适用范围

修复后真实MySQL空schema `mg_p4_digital_20261002_205905_451630` V1–V11完整迁移，数字22/22；`mg_p4_phase4_upgrade_20261002_204603_242086` 从V9代表性用户、产品、订单、Session、Outbox升级V11保留1/1。原并发领取/退款发现的1213死锁保留失败日志；InnoDB外键锁报告、order→user修复和可定序回归见 `docs/phase4-evaluation/d1-review.md`。不是吞异常或用H2替代MySQL。

正常Session HTTP证据：`d1-download-session-check.json`，owner200、other404、anonymous401、private/no-store与attachment/nosniff、重复领取id/claimedAt/expiresAt均不变、已领取退款409。`d1-refund-session-check.json` 未领取订单4经正常浏览器申请及商家审批后REFUNDED/REVOKED，领取下载各409。`d1-version-snapshot-session-check.json` 新供给14.90/assetv2/hash变化，旧订单3仍12.90/assetv1，实际浏览器保存(2).txt与Session下载均248bytes旧hash，旧展示版本购买409。

截图在被忽略的apps/backend/var/phase4-evidence：d1-digital-offer-published、d1-claimed-digital-order、d1-refunded-digital-order、d1-digital-conversation-mobile、d1-digital-orders-mobile、d1-digital-merchant-mobile、d1-reduced-motion-mobile。消费者与商家390宽无横向溢出，旧对话与两数字订单刷新恢复；商家刷新后重新明确身份、打开数字页恢复已选offer#1/v5。浏览器工具直开API路径被客户端拦截，未记成后端越权拒绝；越权由正常Session下载及专项证明。工具download event等待曾超时，实际点击及本机保存文件随后实证通过。

D1仅自建UTF-8测试文件、单offer数量1、支付成功后24小时。未领可退/已领拒退是本Demo冻结规则；不包含生产数字商品政策。默认无guide/key的统一对话Java资料检索已实际通过，没有新增模型调用。阶段4仍须D2、最终组合、main/远端/CI交付。
