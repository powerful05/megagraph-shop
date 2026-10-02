# 阶段4B/4C工程评测与准入裁决 v1

2026-10-03（Asia/Shanghai）。**4B：输入不足，商业准入待验证。4C：输入不足，商业准入待验证。** 本轮没有实施或验收Milvus/Neo4j，也没有评测证据足以否决它们。两项裁决不阻塞4A/4D受控Demo。

## 冻结范围与样本来源

跑结果前沿用[PHASE_4.md](../PHASE_4.md)冻结：K=5、候选最多20，离线搜索p95≤200ms；投影额外端到端预算≤150ms；保留集绝对Recall提高≥0.10、NDCG提高≥0.05，三个独立重复结果一致且无资格回归。B还须真实可用、版本明确的嵌入Provider；C还须确认关系与至少三类多跳查询的收益和可核验路径。**K5/max20仅为离线召回评测；现有GuideAgent仍最多50候选、消费者最多3结果，本材料没有修改该业务契约。**

[queries-v1.json](queries-v1.json)在首轮执行前固定24条工程查询，开发/保留各12，各9条有相关商品、3条预设无结果。每条同时保留自然表达、直接构造的GuideIntent与按SKU码标注的相关性；v1商品均单SKU，因此统计单位是商品，不将多个SKU重复算成多个商品。2为依据测试资料符合用途与硬约束，0为不相关。相关性来自QA人工规则，非模型标注或实际用户反馈。Recall/NDCG只对9条正样本做宏平均；负样本的对应指标为null，不当成满分题。无结果率同时报告全题比例与正样本非预期无结果比例。

开发集用于后续工程诊断，保留集首次结果用于此处记录，**没有据保留集结果调参或修改标注**。评测结果已揭示后不得继续调参再把同一保留集叫作盲测。没有真实用户查询，不制造真实需求；未来商业评测需要新资料/查询、独立保留集，并在运行对照前另冻版本。

目录是已有TestSupplyConfig生成的65件公开LOCAL_TEST/TEST_CONFIRMED资料，含重复变体与共用生成图片，不是65件真实供给：

- [catalog-h2-snapshot-v1.json](catalog-h2-snapshot-v1.json)：旧独立Demo8082公开只读快照；持久H2，未改其数据/进程。
- [catalog-mysql-snapshot-v1.json](catalog-mysql-snapshot-v1.json)：Lead独立MySQL8.4 schema `mg_p4_demo_20261003`、8084公开只读快照；MySQL来源由Lead运行配置核实，HTTP响应本身不证明数据库类型。
- 保留公开响应顺序、商品/商家/版本、确认状态、SKU价格库存、资料与日期，以及响应/文件SHA256。类目字典由TestSupplyConfig固定类别名称与快照categoryId映射，**不是实时数据库分类树导出**，仅适用于这个固定样本。

冻结查询SHA256：`a5b581a37a382bf7d005de0398977fc1dc8e7a3f049f6043fad229041506d40f`。
H2快照SHA256：`c955422792f93adc18f8d9ab25b5693679e4c35e9b9273f249510ed2cf5bcc6c`。
MySQL快照SHA256：`6f2ace533c20a8d3010ea2f153883868a3a3715c13dc842cde991b38095489ea`。
检索实现基线：`a0052d3bd89f7b277f7139ac49bb80658de28195`（阶段3业务实现与阶段4冻结文档）。结果不是新4A或后续4D实现的整仓验收。

## 实际执行结果

[CatalogBaseline.java](CatalogBaseline.java)直接调用生产`GuideCatalogSearch`，不是重写一个近似检索器。基线在完整公开目录上先qualify，再按确认资料里的文字命中数排序，再limit20；相同分数保持输入目录顺序。价格、币种、库存、规格、颜色与材质是硬约束。自然表达没有经过LLM意图解释，**不能用本结果替代端到端对话选品质量评测**。读取路径是MySQL-backed JPA公开目录+Java内存筛选，非SQL内topK关键词检索。

| 数据来源/集合 | 正样本数 | Recall@5 | NDCG@5 | 全题无结果率 | 正样本非预期无结果率 | 最终实时资格过滤 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| H2开发 | 9 | 1.000000 | 1.000000 | 25% | 0% | 未执行实时回查 |
| H2保留 | 9 | 0.747475 | 0.853817 | 25% | 0% | 未执行实时回查 |
| MySQL开发，三个独立JVM | 每次9 | 1.000000 | 1.000000 | 25% | 0% | 每次0/11=0% |
| MySQL保留，三个独立JVM | 每次9 | 0.747475 | 0.853817 | 25% | 0% | 每次0/58=0% |

三个MySQL进程分别启动，固定查询/快照完全相同，所有题top5、Recall/NDCG、无结果与资格过滤一致。每次对69个候选进行实际`GET /api/shop/v1/products/{id}`，检查当前公开资格、相同商品版本及生产qualify；共207次成功资格回查，过滤0，目录稳定。**这不是并发下架、改价或投影陈旧场景的测试，也不是业务GuideAgent运行时审计指标。** 结构过滤在limit前拒绝率开发98.5897%、保留86.1538%，分母是每题完整65件目录；它与最终资格回查过滤率含义不同。

| MySQL重复 | 开发搜索p50/p95 ms | 保留搜索p50/p95 ms | 开发当前商品GET p50/p95 ms | 保留当前商品GET p50/p95 ms |
| --- | --- | --- | --- | --- |
| 1 | 0.107667 / 0.212750 | 0.073792 / 0.310500 | 21.971541 / 56.196667 | 11.278625 / 18.985500 |
| 2 | 0.098958 / 0.176333 | 0.070959 / 0.268959 | 12.034625 / 30.074000 | 8.531417 / 13.037333 |
| 3 | 0.096208 / 0.169958 | 0.063500 / 0.264208 | 9.213208 / 28.612334 | 7.846625 / 9.621958 |

搜索p50/p95每题预热40次、采样100次，合并每集合1200个搜索时长计算最近秩分位数，**只包含Java排序/过滤**，不含数据库公开目录读取、模型解释、网络投影或实时回查。离线≤200ms门槛满足，不等于完整导购响应≤200ms；预热、单机/65件样本也不能保证规模性能。独立[公开目录HTTP记录](http-catalog-mysql-v1.json)为5次预热+30次采样，p50=157.727750ms、p95=200.118333ms，包括数据库资格读取、映射、序列化与HTTP传输；采样与首轮只读资格请求有少量重叠，不作为隔离负载压测。不能把独立请求的p95简单相加成端到端p95。

详细结果：[H2一次](result-h2-v1.json)、[MySQL第1次](result-mysql-v1-run1.json)、[第2次](result-mysql-v1-run2.json)、[第3次](result-mysql-v1-run3.json)。H2首轮使用同一检索算法的初版runner，没有后补的实时GET字段，不将其写为已验证MySQL或实时过滤。

现有`GuideCatalogSearchTest`12项、`ExpandedSupplyIntegrationTest`2项、`BaselineSupplyIntegrationTest`1项，共15项通过，0失败/错误/跳过。后两类为H2持久目录工程验证，不是本次MySQL专项。命令：

```sh
mvn -s .mvn/settings.xml -f apps/backend/pom.xml -Dtest=GuideCatalogSearchTest,ExpandedSupplyIntegrationTest,BaselineSupplyIntegrationTest test
```

QA worktree忽略日志为`apps/backend/var/phase4-evaluation/existing-search-tests.log`，仓库保存上述永久摘要与非敏感指标。编译runner有Jackson旧API/unchecked提示，不涉及业务运行结果；未安装或调用付费模型。

## 工程缺口与不可达门槛

`h07`“整理纸质材料”、`h08`“开会时记下要点”不直接命中现有词串，所有合格候选得0分，结果按目录的新旧顺序打平，混入服饰。它们分别Recall=1/11、2/11，NDCG=0.213986、0.470365；`h06`明确“会议记录/纸质笔记本”Recall=5/11、NDCG=1，已达到该题K5上限。这支持**工程表达存在缺口**，不能证明真实用户有这个问题，也未分离LLM意图规整、简单同义词、文本索引和真实向量的收益。

保留集有三题各11件同级相关，固定K5使其Recall上限为5/11；其余6题上限1。宏平均理论上限是`(6+3×5/11)/9=0.818182`，比当前0.747475最多提高0.070707，**任何候选检索器都无法在这套v1标注上达到冻结ΔRecall≥0.10**。结果揭示的是样本与门槛不匹配，不是向量或图谱无效。保持门槛/样本不变，不跑后改分母、删题、改相关标注或放宽门槛；本版不适用于工程准入/否决比较。未来取得真实资料/查询后另冻v2，再比较，不把重新设计后的结果冒充v1收益。

## B/C独立裁决与缺失输入

**4B：输入不足，商业准入待验证。** 安全核查见当前`application.yml`显式`spring.ai.model.embedding: none`；`application-guide.yml`与已读旧最终报告只证明消费者文本`deepseek-flash`。旧基础报告49/49、扩大14/14的文件元信息和结论已直接核对，它们覆盖多轮意图/选品/资料协议，没有向量空间、embedding版本、Recall/NDCG对照或投影重建证据。源码未见可运行embedding/Milvus检索实现，本轮没有读取/输出密钥，也没有进行模型调用能力探测，因而结论是“未取得真实嵌入能力证据”，不是断言账号永远没有该能力。缺少真实查询、合格的保留集、实际嵌入模型和重复对照；不能用Mock embedding填空，更不能据当前资料否决Milvus价值。

**4C：输入不足，商业准入待验证。** 现有商家成员关系、测试分类父子树、商品已确认属性是明确事实；它们不等同于经商家确认的搭配/替代/互补、适用人群等多跳推荐资料。当前没有关系来源/版本标注数据集、三类多跳查询、可核验路径及收益对照；没有用模型猜测或测试扩展变体补造确认关系。结构过滤或分类父子名字能检索不证明Neo4j必要或无效。

B/C后续解除最小条件：获得授权真实查询与确认资料并冻可达门槛的新保留集；B提供真实可用、版本明确的embedding Provider并比较同资格回查/候选预算/延迟预算，C提供确认关系来源与三类多跳问题及路径。满足收益后再实施Outbox消费者进度、幂等乱序、重试死信、重建版本切换、MySQL资格回查及故障降级。条件性工程未实施，不记成当前功能验收通过。

## 可复现执行

从仓库根目录执行。不会启动完整服务、改数据库、录入商品或调用模型；文件捕获仅允许本机LOCAL_TEST公开资料，原始HTTP响应不包含Cookie。只有Lead可以先按项目交付脚本启动确认数据库类型的独立65件测试服务；不可将旧8082的H2响应重命名成MySQL证据。

```sh
python3 docs/phase4-evaluation/capture-public-snapshot.py http://127.0.0.1:8084/api/shop/v1/products apps/backend/var/phase4-evaluation/mysql-snapshot.json 'MySQL isolated mg_p4_demo_20261003; verified runtime'
sh docs/phase4-evaluation/run-baseline.sh apps/backend/var/phase4-evaluation/mysql-snapshot.json apps/backend/var/phase4-evaluation/run1.json http://127.0.0.1:8084
sh docs/phase4-evaluation/run-baseline.sh apps/backend/var/phase4-evaluation/mysql-snapshot.json apps/backend/var/phase4-evaluation/run2.json http://127.0.0.1:8084
sh docs/phase4-evaluation/run-baseline.sh apps/backend/var/phase4-evaluation/mysql-snapshot.json apps/backend/var/phase4-evaluation/run3.json http://127.0.0.1:8084
python3 docs/phase4-evaluation/measure-catalog-http.py http://127.0.0.1:8084/api/shop/v1/products apps/backend/var/phase4-evaluation/http-catalog.json 'MySQL isolated mg_p4_demo_20261003; verified runtime'
```

无需在线服务也可省略第三参数，重放本仓库存储快照。第三参数也可为另一公开快照文件，此时只统计offlineFreshSnapshotRejectionRate，finalLiveQualificationFilterRate=null。新目录价格、SKU码、产品顺序或内容变了必须保留新快照/版本；不能将新运行直接解释为此处固定v1的可比较结果。runner显式拒绝未知SKU标注或非单SKU/LOCAL_TEST目录。

4A只读审查与待实际执行的失败不变量见[4A审查记录](4A_REVIEW.md)。本目录修改不包含业务代码/API/数据库变更。
