# 更新日志

本文件记录本项目所有值得注意的变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中
- 论文初稿（截止 2027-01-11）：第 5 / 6 章按 `TECH-08` §7「论文素材索引」取图与数据
- 溯源问答模块（RAG）：任务书外扩展，**已决定不实装**；`TECH-07` 的 TC-36~38 如实标「未实施」

## [0.7.0] - 2026-11-19

**`trusted-rag` v2：检索链路与拒答纪律的实质改进（含 v1/v2 公平对比）。**

> 本版在回退上一轮「在线发布 / 容器化 / 框架对照」改动后重新进行，
> 方向从「包装与交付材料」改为「真正改 AI 代码并用数字验证」。

### 新增
- `trusted_rag_v1.py`：冻结 v1 baseline（单路 TF-IDF），用于前后对比
- `compare.py`：同一份 40 条评测集上，用统一口径重算 v1/v2 指标 → `comparison.json`
- `evaluation_baseline.json` / `comparison.json`：可复现的对比证据

### 改进（`trusted_rag.py`）
- **查询意图路由**：新增 `no_anchor`（无批次号/字段词的口语泛问）与
  `comparison_unsupported`（跨记录比较）两类前置拦截，拦截从 4 类增至 6 类
- **BM25 + RRF 混合检索**：TF-IDF 余弦与 Okapi BM25 双路召回，按名次融合（K=60），
  避免不同量纲分数直接相加；为后续接真 embedding 留出可替换骨架
- **查询改写**：口语填充词清理 + 同义说法归一（8 条同义改写中 5 条 Top-1 余弦改善）
- **指标增强**：Top-1 命中率、MRR、拒答原因分布
- 评测集 20 → **40 条**（新增同义改写、口语泛问、跨记录比较、更多拒答边界）

### 实测（`python demo.py` / `python compare.py`）
| 指标 | v1 | v2 |
|---|---|---|
| 答案正确率 | 1.00 | 1.00 |
| **拒答正确率** | **0.7143** | **1.00** |
| R@5 / Top-1 / MRR | 1.00 | 1.00 |
| 失败用例 | Q29/Q30/Q31/Q33 | 无 |

**诚实说明**：答案类与排序类指标两版都是 1.00，说明评测集与语料同分布；
双路融合在当前集上未拉开差距，真正被证明有效的改进是**拒答纪律**。
实体消歧、泛问语义判断两类问题仍未解决，已在 README 中标明。

## [0.6.0] - 2026-11-18

**阶段 E 收口：测试用例逐条真跑、四类核验取证、性能采样、文档口径全部对齐。**
主线（设计 → 链环境 → 后端 → 前端 → 测试）至此完整闭环。

### 新增

- **`scripts/acceptance_test.py`**：阶段 E 验收测试执行器。四个场景实例依次复用 8080：
  正常 / 关闭存证工作器 / 链写入与读取都指向死端口 / 恢复正常。
  一次跑完 **21 条断言（196 s）**，自动产出接口证据、页面截图、性能采样与结构化报告
  `screenshots/04-接口与日志/acceptance-report.json`
- **`frontend/scripts/capture-evidence.mjs`**：单页证据截图（CDP 注入登录态），
  按用例编号精确出图，公开溯源页免登录也能拍
- **四类核验页面证据**：`screenshots/02-公开溯源页/TC-32~35.png`（绿 / 红 / 灰 / 黄）
- **20 份接口原文**：`screenshots/04-接口与日志/TC-01~35.txt`
- **阶段 B 回归日志**：`stage-B-smoke-test.txt`（107 项 / 59.6 s）
- `PROD-03` **v1.2 全文重写**：删掉 Docker / FISCO 2.9 / WeBASE 1.5.4 的从零安装流程，
  改为「30 秒体检 + 复用现有环境 + 11 条真实 FAQ」

### 修复

- **D-02：链不可达时任务状态写错**。`markUnreachable()` 实际写回 `PENDING`，
  而 DDL、`TECH-03` 接口示例、`TECH-07` TC-30、前端 `dict.ts` 都按 `UNREACHABLE` 写。
  改为置 `UNREACHABLE`，并把 `findClaimable()` / `claim()` 放宽到同时认领
  `PENDING` + `UNREACHABLE`——**否则任务会永远卡死**，这是本轮最关键的一处改动。
  S3 实测进 `UNREACHABLE` + `last_error` 有值，S4 恢复正常后 4 条任务自动重试全部 `CONFIRMED`
- **D-03 / D-04（口径与测量方法）**：TC-26 的 `tx_status=0x0` 是 WeBASE 交易状态，
  库里存的节点回执是 `0x1`，用例描述已更正；TC-27 用全局 `totalEvents` 证明幂等会在
  并发写入下给出假阴性，执行器已加「测量前排空队列」，补测 `168→168` 通过

### 测试与回填

| 项 | 结果 |
|---|---|
| `TECH-07` 38 项用例 | **已实施 35 项全部通过**；3 项 AI 扩展如实标「未实施」 |
| 四类核验 | 全部复现且各有截图；`UNREACHABLE` 不显示为「通过」，熔断后 57.9 ms 返回 |
| 链上证据 | 幂等返回码 1 / 冲突返回码 2 / 查链自愈 `totalEvents` 不变，均真链复现 |
| 性能（20 次 × 3 组） | 纯数据库 10.4 ms；1 次链查询 27.0 ms；9 次链查询 105.1 ms |
| 回归 | `mvn test -DforkCount=0` 49 项全绿；`smoke_test.py` 107 项全过 / 59.6 s |

### 文档

- `TECH-07` v1.1（实际列全部回填、证据路径全部指向真实文件）
- `TECH-08` v1.1（执行环境、四场景、重点用例、三组性能明细、D-01~D-04 缺陷表、结论、论文素材索引）
- `TECH-10` §6 改成带状态的 7 处核对表；`TECH-11` 关闭 G6 / G7、阶段 E 打勾
- `TECH-01`（架构图层名 / 选型表 / 拓扑补 `eth_call :8545`）、`TECH-06`（配置块逐字段对齐 `application-dev.yml`）
- `README`：功能表、路线图、测试段、AI 模块取舍说明、文档索引同步

### 说明

- **AI 问答模块不做**是有意取舍：任务书没有这一项，把时间花在硬验收点（存证闭环、
  四类核验、幂等与冲突）的真实链上证据更值。论文按「设计了、未实现」如实写
- TC-33 的篡改只针对本轮新建的独立测试批次，**测完已复原**（`overall` 回到 `PASS`），
  正式演示记录未被修改
- 性能采样是常规单用户交互响应，**不是压测**，论文里不要写成并发能力结论

## [0.5.0] - 2026-11-18

**业务域收口**：销售、冷链、异常处置、召回四块补齐；`ON_SALE → SOLD_OUT` 与
`ABNORMAL ↔ ON_SALE` 两条状态流转、以及「召回联动批量置 `RECALLED`」全部实测通过。

### 新增

- **`POST /api/sales`** 销售登记：条件扣减 `available_qty`（`WHERE status='ON_SALE' AND available_qty >= ?`）、
  卖完自动 `SOLD_OUT`、服务端算金额、`SALE_CREATE` 事件；`GET /api/sales` 分页、`GET /{id}` 详情
- **`POST /api/cold-chain-logs`** 冷链登记：阈值按品类从 `trace.cold-chain.*` 取（含「冻」→ −15℃、含「牛/猪」→ 4℃、兜底 4℃），
  超限自动开 `TEMPERATURE_EXCEEDED` 异常工单并按超出度数分 `LOW/MEDIUM/HIGH`，`COLD_CHAIN` 事件留痕
- **`POST /api/abnormal-events`** 异常登记：在售批次自动冻结为 `ABNORMAL`；**`POST /{id}/dispose`** 处置并可选恢复销售；
  同一异常留下两条 `DISPOSAL` 事件（`stage=REPORTED` / `stage=CLOSED`）
- **`POST /api/recalls`** 召回：批次 → `RECALLED` + 全部销售记录 → `RECALLED` + 召回单 + `RECALL` 事件，四件事同事务；
  返回 `affectedSales` / `recalledQty`
- `SaleRules` / `ColdChainRules` / `AbnormalRules` / `RecallRules`：四组纯规则，配 20 项单测
- `ProcessBatchMapper`：`deductAvailable`（乐观扣减）、`soldOutIfEmpty`（售罄）、`lockAvailable`（失败路径当前读）
- `ColdChainProperties`：`@ConfigurationProperties("trace.cold-chain")`，品类 → 阈值映射

### 修复

- **`GET /api/abnormal-events` 返回 500**：异常可以只挂加工批次（`batch_id` 为 null），而 `Map.of()` 生成的不可变集合
  对 **null 键直接抛 NPE**（`ImmutableCollections$MapN.get`）。空表同样抛，与"表里有没有数据"无关 → 取值前判空

### 说明

- 回归：`smoke_test.py` 扩到 **107 项断言 / 60.0 s**，链上真实产生 8 笔交易；单测 49 项全绿
- 实测数字：售罄后余量精确 `0.000` 且再卖 `40902`；召回 `affectedSales=3`、`recalledQty=28.000`；
  冷链 6.50℃ 自动开 `AB2026...`（MEDIUM）；处置恢复后销售成功
- 论文可写的实现难点：① 销售必须"先扣后判"，先查再改在中午高峰必然卖超；
  ② 召回的加锁顺序必须与销售一致（先 `process_batch` 再 `sale_record`），否则交叉死锁；
  ③ `Map.of()` 空表 `get(null)` 抛 NPE 这个坑与"空指针"三个字毫无提示性

## [0.4.0] - 2026-11-18

**多来源投料打通**：一个加工批次可以消耗多个采购批次，数量在并发下也扣得准。

### 新增

- **`POST /api/process-batches`** 加工投料：`sources[]` 多来源、`inputQty` 由来源汇总、同事务生成 `PROCESS_CREATE` 存证事件
- **`GET /api/process-batches`** 分页（带 `inputQty` / `sourceCount` 汇总）、**`GET /{id}`** 详情（含来源与供应商名）、**`POST /{id}/onsale`** 上架（`CREATED → ON_SALE`，重复调用幂等）
- `ProcessRules`：把「重复引用 / 投入总量 / 产出≤投入 / 单来源余量」四条规则抽成纯函数，配 9 项单测
- `PurchaseBatchMapper`：条件扣减 `deductAvailable`（`WHERE available_qty >= ?`）、扣完结案 `closeIfEmpty`、失败路径当前读 `lockAvailable`
- `SourceRelationMapper.summariseByProcessIds`：一次 GROUP BY 拿全页汇总，避免 N+1
- **`V6__biz_sequence.sql` + `SequenceService`**：业务编号发号器，供应商 / 采购 / 验收 / 加工四类编号统一改走它
- `scripts/concurrency_check.py`：并发扣减验证，12 项断言

### 修复

- **业务编号并发撞唯一键**：原「查当日最大号 + 1」在 4 并发下算出同一个 `PR20261118006`，3 个请求撞 `uk_process_no` 返回 500 → 改用 `biz_sequence` 发号器
- **发号器初版死锁**：先 `SELECT` 判断再 `INSERT` 时，撞重复键会让 InnoDB 给已存在的行加 **S 锁并持有到事务结束**，两个事务各持 S 锁又都要升级 X 锁 → 死锁。改为 `INSERT … ON DUPLICATE KEY UPDATE` 直接拿 X 锁
- **失败提示的余量是过期值**：并发下快照读会提示"剩余 60.000 kg"而实际已是 0 → 失败路径改用 `SELECT … FOR UPDATE` 当前读

### 说明

- 实测：库存 60 kg、4 请求同时各申请 20 kg → 返回码 `[0, 0, 0, 40901]`，余量精确 `0.000`，批次自动 `CLOSED`，三个成功请求拿到三个不同 PR 号
- 链上：`PR20261118024`（2 个来源）已上链，txHash `0x18a24d56…bf63`，block 79；PR 公开溯源 5 条事件全部 `MATCHED`
- 回归：`smoke_test.py` 扩到 **54 项断言**（新增 TC-12~TC-17、上架、PR 回溯），单测 29 项
- 论文可写的实现难点：三条并发坑（撞号 / S 锁死锁 / 快照读旧值），都是"代码看着对、压测就露"的类型

## [0.3.0] - 2026-11-18

**后端主链路全通**：从 HTTP 请求到链上存证再到公开核验，一条线跑通，链上产生真实交易。

### 新增

**公共层（10 个类）**
- `R<T>` 统一返回体（`code/message/data/traceId/timestamp`）、16 个业务错误码、全局异常翻译
- `TraceIdFilter`：每个请求生成 traceId，进 MDC 与响应头，排查问题时先看它
- `PageQuery` / `PageResult`、`Payloads`（payload 构造，自动跳过 null）、`TimeFormats`（兼容两种时间写法）
- `JacksonConfig`：`LocalDateTime` 统一输出 `+08:00`；数量与金额在 VO 层用 String 承载，避免 JS 浮点误差

**链交互（7 个类）**
- `ChainCodec`：`query(bytes32)` 的 selector 常量（`a3b61a9c`，已连真链验证）+ calldata 拼接 + 从 `output` **末字节**取返回码
- `ChainWriteClient`：走 WeBASE-Front `handleWithSign`，区分「链上拒绝（确定没写进去）」与「结果不明（可能写了）」
- `AppendResult`、`ChainRejectedException`、`EvidenceProperties`
- `RpcReadClient` 增强：新增 `queryEventByEventId()`；`eth_call` 返回数据长度异常时抛 `ChainUnavailableException`（原来会抛 `ArrayIndexOutOfBoundsException`，被误判成 500）

**业务模块（72 个类）**
- `auth`：JWT（JJWT 0.12.6）+ BCrypt + `@RequireRole` 拦截器，三种角色
- `supplier` / `purchase`：CRUD + 分页 + `X-Request-Id` 幂等（BR-10）
- `acceptance`：验收登记，**同事务**写验收记录 + 改批次状态 + 出事件快照 + 建存证任务（BR-13），乐观 UPDATE 防重复验收
- `evidence`：`SnapshotFactory`（强制在事务内调用）、`EvidenceTaskService`（条件 UPDATE 认领）、`EvidenceWorker`（三段拆事务、指数退避、BR-14 先查链）、`EvidenceController`
- `trace`：`VerifyStatusResolver`（纯函数，四类状态）、`VerificationService`（带熔断）、`TraceService`（支持 PB 与 **PR 沿 `source_relation` 回溯上游**）、`PublicTraceController`（免登录）
- `idempotency`、`process`（加工批次与来源关系，本轮只读，供溯源回溯）

**迁移脚本**
- `V4__fix_pr03_quantity.sql`：修 V3 里违反 BR-05 的演示数据（肉丸投入 40 却产出 45）
- `V5__request_idempotency.sql`：业务层幂等表

**工具与测试**
- `scripts/smoke_test.py`：一键全链路自检，27 项断言，约 20 秒
- 单元测试 20 项：`VerifyStatusResolverTest`（7）、`ChainCodecTest`（5）、`DigestUtilTest`（5，新增 eventId 格式断言）、`RpcReadClientChainTest`（3，连真链）

### 修复
- **`DigestUtil.newEventId()` 只生成 32 位 hex**：与合约 `bytes32`、`event_snapshot.event_id CHAR(64)` 均不符，改为 32 字节 → 64 位 hex，并加断言
- **`raw_receipt` 写入失败**：上链响应被截断到 500 字符后写入 JSON 列，MySQL 因"非法 JSON"报错，导致存证状态更新失败、无限重试。改为存完整响应，并给回执落库加兜底（回执失败不影响"已确认"主状态）
- `contract-path` 由 `trace` 改为空串（实测必须为空，否则找不到合约）

### 说明
- 实测四类核验状态全部正确：`MATCHED` / `MISMATCHED`（单测）/ `UNCONFIRMED`（关掉 worker）/ `UNREACHABLE`（RPC 指向死端口）。**链不可达时返回 `UNREACHABLE` 而非 `MISMATCHED`**，这是本项目最关键的一条业务红线
- 一次 6 条事件的核验带熔断：链挂了只超时一次，不会让公开页卡 30 秒
- 论文可写的实现难点：① eventId 字节数不一致 ② JSON 列与截断响应 ③ 中文路径下 surefire fork 失败（`mvn test -DforkCount=0`）

## [0.2.0] - 2026-11-18

### 新增
- **`db/migration/V2__missing_tables.sql`**：补齐设计文档承诺但 V1 缺失的 4 张表
  - `cold_chain_log` 冷链温控记录（对应事件类型 `COLD_CHAIN`，支持多运输段）
  - `abnormal_event` 异常事件与处置（对应 `DISPOSAL`，采购/加工批次二选一挂靠）
  - `recall_record` 召回记录（对应 `RECALL`，联动销售记录置 `RECALLED`）
  - `kb_chunk` 知识库分块（RAG，向量存 JSON，供 Java 内存余弦检索）
- **`db/migration/V3__demo_data.sql`**：一整套自洽演示数据
  - 3 家供应商（含 1 家停用）、4 个账号（admin / inspector01 / supplier01 / viewer01）
  - 4 个采购批次、3 个加工批次、4 笔销售、4 条冷链记录、1 条冷链超限异常闭环
  - 刻意埋了三个演示点：**多来源投料**（PR20261118003 同时消耗两个采购批次）、**冷链超限拒收**、**数量严格守恒**
- **`docs/tech/11-落地实施计划.md`**：现状盘点、8 个缺口、GitHub 可复用资产调研、8 周排期（A–E 五阶段）、关键决策点与风险对策

### 变更
- `README.md`：路线图修正 P0 状态（链环境已实际完成），补 TECH-11 索引
- `CHANGELOG.md`：修正 0.1.0 中「V2 演示数据 / V3 AI 扩展」的规划描述，实际为 V2 补表、V3 演示数据

### 说明
- 演示数据中 `PB20261118001` 的验收事件**链上真实存在**（txHash `0xcf8c6e6f…b95a`，block 5）。其规范串 294 字节、摘要 `16decfa5…70bf` 与 `DigestUtilTest` 金标准逐字节一致，因此数据导入后公开溯源页可直接显示「记录已核验 MATCHED」，无需等待程序上链
- 其余业务数据只写业务表、不预置事件快照——快照应由程序运行时生成并上链，避免"造好的假数据"冒充系统能力

## [0.1.0] - 2026-11-18

### 新增
- 仓库初始化：monorepo 目录结构、MIT 许可证、`.gitignore`
- 产品文档：需求规格说明书、用例说明、部署演示指南、答辩操作手册、快速开始、AI 问答功能说明
- 技术文档：架构总览、数据库设计、合约设计、接口文档、环境版本清单、配置说明、AI 模块设计
- 测试文档：测试用例表（38 项设计稿）、测试记录（模板与执行约定）
- **合约源码**：`contract/TraceRegistry.sol`（追加式存证 + 幂等/冲突分支）
- **建表脚本**：`db/migration/V1__init.sql`（11 张表，含外键与 CHECK 约束）
- 设计基线冻结：10 张业务表、9 类存证事件、SHA-256 规范化摘要规范、四类核验状态机

### 说明
- 本版本含设计文档与可执行契约（合约、DDL），业务代码按 `docs/tech/` 中冻结的口径分阶段实现
- 后端/前端工程骨架在 P0–P1 阶段填充
