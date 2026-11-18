# 更新日志

本文件记录本项目所有值得注意的变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中
- 存证写路径接进 Java（`ChainWriteClient`，目前仅在 `scripts/chain_tools.py` 中验证）
- 业务域 11 个模块的 Entity / Mapper / Service / Controller
- 公开溯源页四类核验状态
- 溯源问答模块（RAG）

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
