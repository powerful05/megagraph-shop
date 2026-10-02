# 阶段3C执行证据

2026-10-02持续开发原始记录。下文旧失败、Goal状态和“等待真实输入”是历史检查点。最新用户已明确完整阶段3 Demo口径并授权收尾，当前结论见[Demo交付记录](PHASE_3_DEMO_DELIVERY.md)与本文末尾新增记录；真实业务和生产验收未执行。Git未提交/合入/推送，主目录未修改。

## 已实施与边界

V9增加账号归属会话、用户轮次/请求、结果快照、105秒PENDING租约与attempt栅栏。API只从当前登录账号取owner，正文不能传owner或助手事实。同键同内容重放，不同内容409；同会话最多1PENDING；重试仅最后失败轮，不增加用户消息。模型调用在事务之外；状态及仅含ID/状态的Outbox同事务，事件版本独立递增。

登录会话服务端保存，匿名仅本页内存；不自动把匿名对话导入账号。刷新/切换读取服务端；停止等待只中断浏览器等待，PENDING恢复查询而非重发。退出账号清空本页消息、比较和详情状态。历史卡片仍是展示记录，比较/详情/加购必须读取当前公开商品。

结构化guidance-plan-v1取代对外自由模型商品介绍，通用建议由服务端生成，已确认属性由facts引用。保留追问与正例，不用全部隐藏建议或有限禁词解决。模型获得允许问题/检查项枚举，不包含自由生成属性值。诊断只记录枚举与计数，不记录用户文本、subject或模型原文。

## 自动化证据

- `/tmp/megagraph-stage3-guide-session-final-slice.log`：H2及单元79项，0失败/错误/跳过。
- `/tmp/megagraph-stage3-guidance-availability-tests.log`：加入允许检查项和确认纸质内页适用修正后79项通过；原固定真实模型49/14数据与断言未删改。
- `/tmp/megagraph-stage3-guide-mysql-utc.log`：MySQL8.4.11，新空库`megagraph_phase3_guide_20261002_utc`，11项通过，V1–V9实际迁移。账号归属、并发、幂等、失败重试、租约、迟到attempt、历史19条/12000字及Outbox回滚。
- H2预览不是MySQL运行证据；该MySQL测试mock模型，不冒充真实模型质量。Cookie/JDBC登录追加用例随后单独记录。
- 消费者类型检查和生产构建已通过会话版；商品比较/图片组件是其后的新修改，正在重验，不能沿用旧构建声称最终通过。

原始失败保留在`/tmp/megagraph-stage3-guidance-tests.log`、`/tmp/megagraph-stage3-conversation-tests.log`、`/tmp/megagraph-stage3-conversation-tests-fixed.log`、`/tmp/megagraph-stage3-guide-mysql-root.log`。分别记录旧隐藏建议断言、自由文本校验残留/重复失败Outbox冲突、微秒首次响应与毫秒落库差异、测试注入时间未按UTC写入。首次MySQL失败库`megagraph_phase3_guide_20261002`保留，没有重置；SQL检查显示注入租约15:54、实际UTC07:56，第二空库校准UTC后通过。

比较模板首次类型检查失败：内联回调推断问题；改成类型明确的isCompared函数后通过。新图片组件在隔离Nuxt构建目录的热更新中未自动注册，浏览器发现无图及Vue警告，已显式导入；实际图片复核待补。对应日志`/tmp/megagraph-stage3-comparison-typecheck*.log`，不隐藏失败。

## 浏览器证据

预览`http://127.0.0.1:3002/`接8082扩大65件测试目录；本地H2，真实deepseek-flash；生成图片、测试价格/SKU和Mock支付标识保留。只重启本窗口8082，没有动主目录/其他服务。

实际账号对话：30元以内A5纸本→钢笔防洇墨资料追问→改25元。3条用户消息刷新后各出现一次；已确认纸质内页引用保留，未确认防洇墨明确未知；25元没有匹配商品。停止等待并新建对话，服务器迟到回复未写入新对话，切回原对话取得终态。

截图`apps/backend/var/trial-evidence/stage3-session-desktop.jpg`记录1280×800恢复后的会话。后续布局/比较修改仍需新的最终截图；390×844测试及退出/跨账号、故障重试、比较/交易闭环正在继续，实体手机软键盘未验证。

模型现场发现：纸本建议原仅检查事实标签“内页”，没有接受已确认材质“纸质内页”，导致有用检查被替换为通用建议；已修正并加适用范围枚举。想比较两件但只有一件符合60元预算时，模型曾返回与候选数量不一致的空结果，服务端拒绝，没有凭空凑数；提示已明确允许仅返回符合的一件，最终专项与49/14待验证，不能把这条现场失败写为通过。

## 接续位置

Cookie追加用例H2独立12项通过（`/tmp/megagraph-stage3-guide-session-cookie.log`）；新MySQL空库`megagraph_phase3_guide_cookie_20261002`只运行新增Cookie/JDBC登录/退出用例，1项通过（`/tmp/megagraph-stage3-guide-mysql-cookie.log`），不无理由重跑此前11项。比较版消费者生产构建通过（`/tmp/megagraph-stage3-comparison-build.log`），随后轮询状态/详情焦点微调仍需最终构建。

当前8082已经重启加载guidanceAvailability。现场新会话“找两款400元以内A5纸本”一次得到2件MATCHED、CONFIRMED_DIFFERENCES与PAPER_USE，无再生成；2.637秒，保留有用建议且未宣称防洇墨。桌面1280×800与手机390×844比较读取当前资料，两张图片实际加载；手机pageWidth=390，面板366px，内部并列区域480px，可横向浏览，没有撑宽页面。截图`stage3-comparison-desktop.jpg`、`stage3-comparison-mobile.jpg`。手机侧栏Shift+Tab限制在历史控件，Escape回到触发按钮；隐藏侧栏不再被聚焦，截图`stage3-session-mobile.jpg`。

手机从比较进入两家当前详情，选当前SKU并加购。下单组`CGf741296699fc4941ba8ef5b452efc6c8`，两商家3件总458（B纸本2件58，A扩展纸本1件400）；两者都是试用资料，非真实交易。B子订单`MGBD586FACB4EA46469E903AE0`Mock成功/CONFIRMED；A子订单`MGE0C8CEAECAD8406083DB2CAD`Mock失败/RELEASED，互不影响。截图`stage3-orders-mobile.jpg`；公开当前库存另行核对。未自动发货、未接真实支付。

恢复位置：消费者3002与后端8082运行；商家工作台已启动5174、代理8082，待实际检查。用`localhost:5174`而消费者用`127.0.0.1:3002`区分本地浏览器Cookie主机，避免商家测试登录覆盖消费者登录；这不是新鉴权方案，服务端归属照常校验。继续商家资料更新/确认/下架、比较失效、库存不足和失败重试。最终49+14、阶段pnpm verify、全矩阵与外部真实资料/反馈仍待完成，Goal保持active。

## 后续实际检查与修复

以上“恢复位置”为此前检查点。随后已经实际完成：

- 商家A下架商品#45，仍打开的旧详情加购被后端拒绝，购物袋仍0；比较不显示旧400元作为现时价格。改价399、内页180页后保存，状态READY_FOR_CONFIRMATION、发布禁用；重新确认发布后，比较读取399/180页/v8并提示与历史不同。再经正常下架/修改/确认/发布恢复400/160页，未重置数据库。截图`stage3-off-shelf-comparison.jpg`、`stage3-merchant-reconfirmation.jpg`、`stage3-current-version-comparison.jpg`。
- 上传既有生成纸本图片，经Mock文件名草稿、人工选择纸本末级类目/通用实物、SKU及规格保存，新增草稿#66保持UNPUBLISHED；不冒充真实供给，也不进入固定65件公开目录。截图`stage3-merchant-new-draft.jpg`。
- 购物袋2件#45，当前库存随后改1并确认发布，结算报SKU_STOCK_INSUFFICIENT，订单仍4，无新增组。库存正常恢复20，新增购物项移除，购物袋0。截图`stage3-stock-insufficient.jpg`。
- 退出后账号消息与比较清空；另一已存在试用账号空历史，原账号登录后恢复原会话。截图`stage3-other-account-empty.jpg`。故障页共用相应主机Cookie；测试完成后商家工作台已恢复商家账号，不误把另一账号历史归到消费者。
- 隔离3003→8089→8082故障代理仅本机，注入导购POST503：显示实际故障和重试，无伪造商品；恢复后原轮重试获得真实deepseek-flash、用户消息一次。截图`stage3-request-failure.jpg`、`stage3-request-retry-success.jpg`。测试故障脚本在忽略的`apps/backend/var/stage3-fault-proxy.mjs`，不修改业务鉴权。
- 图片GET503，换未缓存本地主机入口后，卡片/详情显示“图片暂不可用”，属性、SKU和加购仍可用；新增试用购物项随后移除。截图`stage3-image-fallback-detail.jpg`。不把先前缓存命中误写为图片失败已注入。
- “减少动态”可手动选择，默认尊重系统媒体偏好；手动开启后实际transitionDuration=`1e-05s`（0.01ms）。390像素侧栏Shift+Tab返回末尾复选框、Escape回历史按钮，pageWidth=390；该手动控件的验证不是强制修改OS偏好的验证。
- 真实发送中停止等待，然后刷新；恢复原会话最终回复，3条用户消息各一次。无额外“上游已取消”承诺。两款60元内A5纸本无提示的现场追问只返回符合的一件，没有凑第二件或再称候选为空。截图`stage3-stop-refresh-restored.jpg`。

## 最终模型协议回归

首轮新协议基础49题为48/49，失败`size-not-guaranteed`：只问改善场景，没有回应M标签不能保证合身。报告`guide-evaluation-stage3-guidance-baseline.json`、日志`megagraph-stage3-guidance-baseline-evaluation.log`保留。不是删题或放宽断言。

补充通用FIT_CONFIRMATION及具体SIZE追问适用范围，澄清主回复不再重复泛PURPOSE问题。后端56项通过，专项真实1/1后，在最终模型/检索/事实实现上分别完整执行一次：

| 集合 | 模型 | 题 / 轮 | 结果 | 报告（apps/backend/var） |
| --- | --- | --- | --- | --- |
| 基础五件 | deepseek-flash | 49 / 69 | 49通过 | guide-evaluation-stage3-fit-baseline-final.json |
| 扩大65件 | deepseek-flash | 14 / 17 | 14通过 | guide-evaluation-stage3-fit-expanded-final.json |

首轮失败和修复后的集合为不同实现。最终最大单题耗时（可多轮）5222/4119ms，不是未来或实体网络延迟保证。随后只有页面/媒体及交易测试变化，未无理由重复付费评测。人工阅读实际M适配、棉粘成分与未知触感、纸本材料/装订、防洇墨伪造性能、预算改口、监控取消、数字授权和服务范围样本；确认事实原样引用与通用建议分开。未声称所有自然句逐句人工验收或真实用户参与。

## 媒体资源与性能边界

五图共4,743,410 bytes，两张PNG为2,382,480/1,949,746 bytes。新增公开media可选320/640/960缩略响应，先现时资格校验，按比例缩小，不改源文件；不透明图JPEG，透明图PNG，不可解码/超界资源退回原响应。

真实HTTP：文件袋320/640/960为19,357/64,544/135,697 bytes；纸本为16,034/48,803/98,702 bytes。原图仍正常200 PNG；640图较原PNG分别减少约97.3%/97.5%。新增后端媒体3项与资料/发布10项共13通过，消费者类型检查通过。现时下架图片与非法宽度也有HTTP集成断言。

最终实际浏览器读取`?width=640`、图片complete且naturalWidth>0；390像素无横向溢出，桌面比较两列正常，图片与生成原图主体一致。截图`stage3-final-comparison-desktop.jpg`，移动截图`stage3-final-mobile.jpg`/`stage3-stop-refresh-restored.jpg`（后两张分别记录动态控件与停止恢复，缩略图上线后另行实际复核）。页面canvas数量0，未使用WebGL/3D依赖；没有强制禁用GPU或测试实体手机软键盘、触控延迟与真实慢网，不能将本地资源缩减当成这些项目通过。

## 最终数据库与交易证据

MySQL8.4.11独立新库`megagraph_phase3_cross_20261002_v9`：跨商家原5项全部通过、V1–V9初始化实际完成，日志`megagraph-stage3-mysql-cross-v9.log`。不复用/重置原库，不把H2预览当MySQL。

为价格变化补充明确回归：加购价格10/20→结算前12.34/56.78→订单69.12；之后改199/299，原订单金额、币种、身份和全部商品快照不变。首个H2断言误包含所有响应时间，首次微秒与落库毫秒不同导致失败（`megagraph-stage3-price-recheck-h2.log`），金额/快照本身一致；断言收敛到该任务的身份/金额/币种/全部items，不改变生产金额逻辑。新独立库`megagraph_phase3_price_20261002`只执行这一个新MySQL用例，1通过、V1–V9；日志`megagraph-stage3-price-recheck-mysql.log`。最终H2整仓运行会覆盖该修正断言。

## 交付口径

源码与产物/截图/本轮日志精确密钥扫描712文件0命中，`.env.local`模式600且忽略；没有重新读取RTF。源文件、编译后的前端和原始故障/成功日志分开留存；大运行证据不提交。

真实供给、用户反馈、实体设备及最终负责人结论仍为空；[录入与集中验收材料](PHASE_3_TRIAL_ACCEPTANCE.md)已提供可操作路径、字段模板及空反馈表。Goal不complete，不进入阶段4，不自动提交/合入/推送。

## 最终技术包收尾

2026-10-02 17:46:37 +08:00，`pnpm verify`正常结束：163项后端测试中142通过、21 MySQL专项跳过，0失败/错误；双前端类型检查与生产构建、后端package全部成功。日志`/tmp/megagraph-stage3-price-final-verify.log`并归档至`apps/backend/var/trial-evidence/`。升级失败/幂等唯一键错误是对应负例测试输出，不是测试集合失败。Rollup注释和Mockito动态agent有非阻断构建警告，原日志保留。

独立MySQL验证为会话11+Cookie1+跨商家5+新价格快照1，共18项通过，四个新空库均实际初始化V1–V9。常规运行21项跳过并未全部在该Goal补跑；另外3个迁移/升级/商业冒烟没有新增本轮独立结果，不混用历史证据。

缩略图最终移动截图`stage3-final-thumbnail-mobile.jpg`为390×844，主体图片实际加载、底部输入可用；空白桌面首屏`stage3-final-agent-first-screen.jpg`为1280×800，无预置商品墙，比较清空、历史保留。之后绑定交付页并解除临时视口，实际回到523×903默认面板。消费者和商家标签保留为交付入口，故障标签关闭；本窗口测试进程3003/8089正常停止，不动其余服务。

收尾精确密钥扫描包括新增最终日志、源码、两端前端产物及截图证据，共777文件，0命中；`.env.local`仍600且忽略，密钥不输出。`git diff --check`通过。三个试用页面实际HTTP 200，基础/扩大公开目录分别5/65件，草稿#66不在公开响应中。独立分支仍有既有及本轮未提交改动，没有提交/合入/推送；主目录只读检查仍main领先远端10个提交、原有AGENTS/README/架构/3A设计改动保持。

技术检查后的恢复点：3002→8082扩大目录、3001→8081基础目录，均为本地H2和真实消费者DeepSeek；5174商家仍为Mock草稿生成。商品#45恢复400/160页/库存20并确认发布，公开测试目录65件；草稿#66未发布，购物袋0、4个旧测试子订单保留。所有可自主实现与相称验证已收尾，下一步仅接收授权真实商家资料/实际确认、真实用户反馈和负责人集中验收。缺失输入不以测试数据补造，不将Goal标记complete，不开始阶段4。

## 补充数据库完成性审计

2026-10-02 17:57:52 +08:00，在最后审计中补齐此前只有历史结果的3项MySQL冒烟，不重复已通过用例或付费模型，也不修改实现与迁移：

- `megagraph_stage3_smoke_1790935061787`：空MySQL8.4库实际V1–V9初始化及校验，资料/订单快照字段、会话和交易唯一约束、支付事件及邀请码重复拒绝通过。
- `megagraph_stage3_upgrade_1790935061787`：V1–V5后写入既有账号、订单、Outbox和支付事件，再V6–V9升级及校验，旧记录与相应状态保留，账号标准化通过。测试范围不等同于任意真实生产数据升级。
- `megagraph_stage3_commerce_1790935061787`：V1–V9初始化、JDBC会话登录、单商家订单、异步Mock支付及订单PAID、库存CONFIRMED、支付事件APPLIED/ORDER_PAID Outbox、会话过期及清理通过。

日志`/tmp/megagraph-stage3-remaining-mysql-smoke.log`（归档同名文件）记录3项、0失败/错误/跳过，命令退出0。本Goal独立MySQL结果由18增加为21项；常规`pnpm verify`的21项跳过不改写为该次已执行，两个执行方式分报。没有更改业务代码，不因仅补测试证据重跑整仓或49/14。

Flyway日志仍提示内置版本对MySQL8.4没有声明正式测试支持；本次实际迁移/升级通过是本项目范围证据，不消除库级兼容性提示，不借此做无关依赖升级。7个独立测试库均保留，不重置预览或旧失败库。真实资料/反馈/负责人结论仍缺失，Goal保持未完成、等待具体外部输入。

## 完整阶段3 Demo收尾

2026-10-02负责人最新确认没有真实商品/额外参与者，主要本人测试，“我目前还没发现问题”，授权完成整个阶段3 Demo验收收尾。此前真实外部输入阻塞仅为历史范围，不再阻塞当前Demo；本轮平台无持续Goal，不改造或伪造其状态。

最新含紧凑目录的`pnpm verify`于19:25:01 +08:00退出0，142通过/21 MySQL跳过/0失败，双前端类型检查与构建、后端package成功；日志`/tmp/megagraph-phase3-demo-closeout-verify.log`归档同名。独立MySQL21项与最终49/49、14/14报告已再次读取核对，协议无变化，不重复付费回归。

新增实际浏览器3轮从模糊会议记录需求、A5/400预算到不同商家，成功取得A#45/400与B#41/390；1280×800及390×844当前比较图加载，小屏scrollWidth=390。两家各一件下单组`CG41959ca8566242d3bb04aec3c8eea68d`共790：B`MGA1405C7FEC084D0EAF17FD5F`Mock成功/CONFIRMED，A`MG9959D0F2DB514694AE4DD0AE`Mock失败/RELEASED。刷新终态保持，3条用户消息各一次；原5个订单未操作，新增2个后7个，购物袋0，A#45库存20/B#41库存19。未改库恢复成交库存，不操作原退款申请或旧订单。

加购会自动打开购物袋，因此后续点击已关闭的详情按钮未匹配；这是自动化定位已过期，不是业务失败。首次名为desktop的订单截图实际仍390宽，核对DOM到1280宽后重新拍摄覆盖，未把resize调用当成桌面证据。新截图、限制、入口、密钥/diff检查和Git边界集中于[完整Demo交付记录](PHASE_3_DEMO_DELIVERY.md)。阶段3 Demo收尾完成，主目录未修改、未提交/合入/推送，不进入阶段4。
