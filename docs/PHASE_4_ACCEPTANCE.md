# 阶段4验收矩阵

| 标准 | 当前结论 | 证据 |
|---|---|---|
| 4A持久异步/幂等/租约/恢复/产物/权限/Outbox | 受控Mock通过 | MySQL15/15、真实SIGKILL恢复、4a-real-crash.json |
| 4A工作台与商家至消费者闭环 | 通过 | job4→product68(v4)→导购→SKU→跨商家Mock拆单；task3重试/取消/刷新/历史 |
| B语义召回准入 | 输入不足，商业准入待验证；未实施Milvus | docs/phase4-evaluation/README.md，固定MySQL3次基线 |
| C关系多跳准入 | 输入不足，商业准入待验证；未实施Neo4j | 同上，无确认关系与多跳对照 |
| 4D1数字购买/领取/下载/退款 | 受控数字文件Demo通过 | MySQL22/22/0skip；offer#1人工确认发布→订单#3实存248bytes原hash；订单#4未领退款REVOKED/下载409；变价换文件后旧单仍原版本 |
| 4D2预约/容量/取消/退款/履行 | 受控测试预约Demo通过 | MySQL22/22；浏览器双账号最后容量/取消再购/退款释放/OWNER显式完成；正常Session边界与隔离 |
| MySQL空库/从V9升级/并发不变量 | 通过，均独立执行且0skip | Ingestion15/15、Digital22/22、Appointment22/22、最终V9→V12 Upgrade1/1；MySQL8.4.11 |
| 前端typecheck/build、根pnpm verify | 最终组合通过 | d2-final-integration-verify.log：283总/202执行通过/81条件skip/0失败错误；两端类型与生产构建、后端打包通过 |
| 桌面/移动/键盘/减少动态浏览器 | 4A/D1/D2通过 | 稳定桌面与390px截图、订单/详情/商家确认及完成Enter、减少动态checked；非实体手机验证 |
| 主干集成/远端/CI | 待验收后执行 | 私有远端已确认 |
| 真实视觉/嵌入模型 | 未验证 | 消费者文本模型不证明能力 |

## D1证据与适用范围

修复后真实MySQL空schema `mg_p4_digital_20261002_205905_451630` V1–V11完整迁移，数字22/22；`mg_p4_phase4_upgrade_20261002_204603_242086` 从V9代表性用户、产品、订单、Session、Outbox升级V11保留1/1。原并发领取/退款发现的1213死锁保留失败日志；InnoDB外键锁报告、order→user修复和可定序回归见 `docs/phase4-evaluation/d1-review.md`。不是吞异常或用H2替代MySQL。

正常Session HTTP证据：`d1-download-session-check.json`，owner200、other404、anonymous401、private/no-store与attachment/nosniff、重复领取id/claimedAt/expiresAt均不变、已领取退款409。`d1-refund-session-check.json` 未领取订单4经正常浏览器申请及商家审批后REFUNDED/REVOKED，领取下载各409。`d1-version-snapshot-session-check.json` 新供给14.90/assetv2/hash变化，旧订单3仍12.90/assetv1，实际浏览器保存(2).txt与Session下载均248bytes旧hash，旧展示版本购买409。

截图在被忽略的apps/backend/var/phase4-evidence：d1-digital-offer-published、d1-claimed-digital-order、d1-refunded-digital-order、d1-digital-conversation-mobile、d1-digital-orders-mobile、d1-digital-merchant-mobile、d1-reduced-motion-mobile。消费者与商家390宽无横向溢出，旧对话与两数字订单刷新恢复；商家刷新后重新明确身份、打开数字页恢复已选offer#1/v5。浏览器工具直开API路径被客户端拦截，未记成后端越权拒绝；越权由正常Session下载及专项证明。工具download event等待曾超时，实际点击及本机保存文件随后实证通过。

D1仅自建UTF-8测试文件、单offer数量1、支付成功后24小时。未领可退/已领拒退是本Demo冻结规则；不包含生产数字商品政策。默认无guide/key的统一对话Java资料检索已实际通过，没有新增模型调用。D2与最终组合现已通过，main/远端/CI按后续交付记录核实。


## D2最终证据与适用范围

最终代码5965869。修复后MySQL空库 `mg_p4_appointment_20261003_062150_100114` V1–V12迁移并预约22/22、0skip；另一空库 `mg_p4_phase4_upgrade_20261003_062240_660481` V9代表性用户、商品/SKU、订单/支付、Session/属性、对话、入库历史及Outbox升级V12保留1/1。首轮20例的2个mutex死锁错误保留，修复为重复键no-op upsert直接获取X，原20例未删除或降断言，新增2个已有mutex定序检查。详见独立QA d2-review.md，H2与MySQL分别记录。

浏览器在sameDemo商家A创建offer1/29.90CNY，两个北京时间固定时段各容量1，确认后发布v4。两个既有账号通过统一对话发现服务、当前详情选择同slot1，以Promise.all提交两个UI点击；order5预占，另一账号收到当前时段无名额。UI点击不声称等于数据库定序，数据库并行不变量另由真实MySQL证明。order5主动取消释放，另一账号order6重购/Mock付款仅CONFIRMED、未履行；申请退款后商家模拟完成按钮disabled，经OWNER审批异步退款后REFUNDED/RELEASED。再购买order7、付款仍仅确认，OWNER Enter显式模拟测试完成后FULFILLED。已完成退款409。所有结果是测试，不实际预约或履约。

`d2-session-business-check.json` 正常Session/CSRF记录orders5/6/7完整预约事实、他账号404、他商家complete404、匿名401、无CSRF403、已完成退款409、误走数字claim404/实物收货409、混items400。slot2先合法HELD，正常商家API关闭后期限内Mock付款仍CONFIRMED（order8），关闭只阻止新购。JSON证明北京时间14:00对应UTC06:00。`d2-final-future-slot-snapshot.json`：商家小屏新增slot3/10月5日10:00–11:00北京时间/容量2，重新确认发布v7；order7仍购买v4和原slot快照。slot3当前剩余2可继续演示，时段过后由正常业务新增未来时段，不修改旧事实。

sameDemo增量V11→V12后 `d2-v11-preservation-session-check.json` 保留D1订单3/4、旧文件248bytes原SHA及原实物两单与product68；生成器逐字段比较基线记录，QA独立复核可获得的安全摘要。真实MySQL只读history V10/11/12 success=1另在 `d2-demo-migration-sql-check.json`，无直接SQL业务写入或重置。

截图ignored `apps/backend/var/phase4-evidence`：d2-appointment-published、d2-last-seat-winner-held、d2-last-seat-loser、d2-confirmed-not-fulfilled、d2-refunded-released、d2-explicit-fulfilled、d2-mobile-restored-orders、d2-mobile-merchant-restored、d2-mobile-published-future-slot、d2-mobile-reduced-motion。merchant-refunded-released截图捕获时退款已成功，不作为处理中截图；处理中disabled由当时CUA状态、源码守卫与MySQL互斥证据证明。消费者/商家重开恢复，390×844实际innerWidth/scrollWidth各390、无横向溢出；Enter与减少动态checked已观察和稳定截图核实。缺失控件曾因小屏历史折叠而locator超时，展开后正常操作，不是业务失败。

根最终283总/202执行/81条件skip，跳过的MySQL项由上述独立真实库执行覆盖相应新功能；不能把81跳过说成通过。已验收必做4A/D1/D2；B/C各为输入不足裁决，Milvus/Neo4j未实现。真实视觉/OCR/嵌入模型、真实供给/用户、实体设备、生产支付/履约待正式试用。
