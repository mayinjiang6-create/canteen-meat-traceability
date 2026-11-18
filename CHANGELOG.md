# 更新日志

本文件记录本项目所有值得注意的变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中
- 加工投料（多来源）、销售、召回、冷链、异常处置的接口
- 前端：Vue3 管理端 + 公开溯源页面
- 溯源问答模块（RAG）
- 30 项测试用例逐条执行、`TECH-08` 测试记录回填
- `TECH-10` §6 列出的 7 处文档同步

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
