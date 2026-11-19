# 更新日志

本文件记录本项目所有值得注意的变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中
- 生产化加固：多实例部署、链上事件归档、告警接入
- 溯源问答模块（RAG）：设计已完成，**本期不排期接入**；`TECH-07` 的 TC-36~38 标「未实施」

## [1.4.0] - 2026-11-19

**把「交付形态」补齐：容器化 + 现场部署清单 + CI 门禁，
并把可信 RAG 的评测扩到 80 条（60 公开 + 20 held-out）、新增双口径与执行轨迹。**

### 新增 · 容器化交付（P0）
- `deploy/docker-compose.yml`：MySQL + 后端 + 前端三服务，健康检查 + 依赖顺序 + 数据卷；
  **链为可选外部依赖**，不接链时业务照常、公开溯源返回 `UNREACHABLE`
- `backend/Dockerfile`：多阶段（Maven 构建 → JRE 运行），非 root、HEALTHCHECK，
  `mvn package` 而不是 `spring-boot:run`
- `frontend/Dockerfile` + `frontend/nginx.conf`：Node 构建 → Nginx 托管，`/api` 反代
  使用 Docker 内置 DNS（后端重启换 IP 不会 502），SPA history 兜底
- `deploy/.env.example`：端口 / 口令 / 链参数模板；`.env` 已被 gitignore
- `deploy/check_deploy.py`：**35 项**静态自检（端口冲突、构建上下文、jar 名与 pom 一致、
  反代目标、环境变量闭环、密钥不入库）
- `docs/product/09-客户现场部署清单.md`（PROD-09）：十节作业手册，含端口表、密钥注入、
  链可选与降级、备份恢复、升级回滚、**14 条客户验收 Checklist**、12 类故障排查

### 新增 · CI（P0）
- `.github/workflows/ci.yml`：推送即跑 60 条公开评测 + 20 条 held-out + RAGAS 口径 +
  v1/v2 对比 + 63 项应用断言 + 页面一致性 + 49 项单测 + 交付包自检 + `docker compose config`
- 明确不跑 `smoke_test.py` / `acceptance_test.py`（需真链与本机 MySQL），边界写进文档

### 变更 · 配置可注入（P0）
- `application-dev.yml` 的 JDBC / WeBASE / RPC / 合约地址 / 存证工作器改为
  `${ENV:默认值}` 形式，默认值与原先完全一致，本机开发行为不变
- 新增 `GET /api/health` 存活探针（不查库不查链），加入 `WebConfig` 免鉴权白名单；
  TECH-03 补接口说明，TECH-06 改为三套环境对照

### 新增 · 评测与可观测（P3）
- 评测集 40 → **60 条公开**（39 应回答 + 21 应拒答）
- 新增 `eval_holdout.jsonl` **20 条 held-out**（13 应回答 + 7 应拒答）+ `holdout.py`
  - **首跑 18/20，抓到两个真缺陷，修完 20/20（期望值未改）**：
    ①「哪些批次做过异常处置」误判无锚点 → 补齐 `ANCHOR_TERMS`；
    ②「PB20261118001 的检疫合格证号」**答出了 PB20261118035 的证号** →
       命中集按被点名批次过滤，过滤后为空则拒答（跨批次串台）
- 新增 `ragas_like.py`：faithfulness / answer relevance / context precision / context recall 的
  零依赖确定性近似，产 `ragas_like.json`，并在 README 写清与官方 ragas 的差距
- 新增 `app/export_trace.mjs` + `app/trace.jsonl`：18 条代表性问题的执行轨迹
  （问题 / 拒答原因 / 工具链 / 每步耗时 / 引用编号），对应「错题本 / 监控告警」
- `product.html` 重建（60 条评测内嵌）；模板里的条数改为占位符，扩集不再改模板

### 变更 · 离线软件（P3 副产物）
- 新增第 12 个工具 `list_acceptances`：按结论筛选验收记录
- 锚点词表补「验收 / 合格 / 不合格 / 留样 / 证号 / 许可证」，修掉「有没有验收不合格的？」误拒
- `verify_app.mjs` 断言同步为 12 个工具；重跑核心 63/0、浏览器 14/0

### 新增 · 框架对照与产品材料（P1 / P4）
- `deploy/dify/`：190 块知识库（`kb_meat_trace.md` + `build_kb.py`）、7 条硬约束 Prompt、
  同口径打分脚本 `score_platform.py`、搭建清单 README
- 框架选型对照文档：三方案对照 + 7 条约束逐条判定（并入 `deploy/dify/README.md`）
  （4 条平台做不到）+ 判断清单 + 4 条选型口径。**平台未实跑，文档里写明**
- 产品说明与 90 秒演示脚本

### 新增 · 语义检索对照（P5，结论是「不换」）
- `trusted-rag/fetch_model.py`：从 ModelScope 拉 `BAAI/bge-small-zh-v1.5`（95 MB，权重不入库）
- `trusted-rag/embed_eval.py`：只替换检索器（词面 → BGE 余弦），意图路由 / 六类拦截 /
  字段校验 / 批次过滤 / grounded 全不动，同一份 60 + 20 条题重跑
- **结果：答案正确率 1.00 → 0.9231（公开集与 held-out 一致），拒答仍 1.00**；
  失败全是"检疫证号"类精确查询（稠密检索把语义相近的其他记录排在前面）；
  一条真实收益：「猪肉合格不？」从硬答一条 `FAIL` 记录变成正确拒答
- **决定：保留词面检索，方向改为 BM25 + 向量混合召回**；同名供应商消歧仍未解决
- 报告 `embedding_eval.json`；叙述见 `trusted-rag/README.md`「语义检索对照」

### 修复 · 文档口径（P2）
- `trusted-rag/README.md` 159–166 行表格表头重复串行（渲染错乱）
- 根 README 补「三个 AI 不要混为一谈」表：Java 主项目未实装 / `trusted-rag` 已实现 / `app` 已实现
- 项目总览文档加状态提示；补齐清单第 3 项回填
- 对外材料数字同步为 80 条（60 公开 + 20 held-out）

### 验证
- `deploy/check_deploy.py`：**PASS 35 / FAIL 0**
- `python demo.py`：60 条全过（答案 1.00 / 拒答 1.00）；`python holdout.py`：20 条全过
- `python compare.py`：v1 拒答 0.7143 → v2 1.00（失败 6 条全修）
- `node verify_app.mjs` 63/0、`node verify_app_browser.mjs` 14/0、`node verify_product.mjs` 8 项一致
- `mvn test -DforkCount=0`：49 项（3 项需真链自动跳过）
- `python embed_eval.py`（需 torch + transformers）：语义检索 vs 词面检索，80 条同题对照已出数
- 本机实跑后端 jar：`/api/health` 200、登录 `code=0`、无链时公开溯源 1 条 `UNREACHABLE`
- **未验证**：容器构建与 `docker compose up`（本机 Hyper-V 关闭、Docker Desktop 不可用）；
  Dify 平台实跑（同上原因）——两处都在文档里如实标注

## [1.3.0] - 2026-11-19

**修复「很多问题都搜不到」：从固定关键词路由升级为三层意图覆盖。**

用户截图指出「计算一下所有批次的肉类有多少斤」被拒答「未匹配到可用工具」。
根因是意图路由写死为关键词匹配，换个说法就失效。

### 改动
- 新增第 11 个工具 `compute_stats`：代码精确汇总采购/加工/销售数量、批次与
  供应商数，含 kg→斤 换算，**不让模型做算术**；离线也能回答计算类问题
- 新增 `planWithLLM()` **LLM 工具规划器**：确定性路由匹配不到时，让模型只输出
  计划 JSON（工具白名单 11 个、最多 4 步），代码执行 + 参数兜底 + 轨迹记录 +
  非法工具过滤 + 失败回退；越界护栏不交给模型
- 新增 `runAgentSmart()`：确定性优先（常见问法零成本可复现）→ 自由提问才用
  LLM 规划 → LLM 组织答案；`executeSteps()` 抽出执行循环供两者复用
- 拒答文案改为引导式（给示例问法），不再像系统报错
- 示例问题新增「所有批次的肉类一共有多少斤？」「帮我汇总一下采购、加工和销售的数量」
- `capture_llm_real.mjs` 支持 `LLM_QUESTION` / `LLM_OUT` / `LLM_PNG` 环境变量

### 验证
- `node verify_app.mjs`：**PASS 63 / FAIL 0**（新增 12 项：计算类离线可答、
  斤换算数值精确、换说法命中、规划器产出、白名单过滤、自由提问不拒答、
  轨迹仍由代码执行、答案带依据、护栏不绕过、规划失败回退、工具集 11 个）
- `node verify_app_browser.mjs`：**PASS 14 / FAIL 0**（新增 2 项：用户截图那个
  问法在真实浏览器里不再拒答、答案标明"代码精确汇总"）
- `node verify_local.mjs`：**PASS 4 / FAIL 0**
- **真实模型自由提问**：「给我一份业务整体概览」→ LLM 规划 4 步
  `compute_stats → list_suppliers → list_abnormals → list_recalls`，
  生成 805 字带 `[T1][T2]` 依据的概览，HTTP 200 / 2483 tokens。
  证据 `app/evaluation_llm_planner.json` + `screenshots/03-智能体自由提问规划.png`

### 安全
证据文件均不含密钥；`app.local.html` 仍被 gitignore；全仓库扫描待提交文件无真实密钥。

## [1.2.0] - 2026-11-19

**接入真实 LLM 并完成调用取证（用户明确授权）。**

### 接入
- 用户提供 DeepSeek Key，明确要求直接接入；按职责边界，
  **密钥未写入任何公开仓库文件**，而是：
  - `app/make_local.py`：从环境变量读 Key，生成 `app/app.local.html`
    （本机已接入版，双击即 LLM 增强模式，已加入 .gitignore）
  - 公开版 `app.html` 依旧零密钥，默认离线确定性模式
- `app/capture_llm_real.mjs`：真实浏览器真实调用取证，输出脱敏
  `app/evaluation_llm_real.json` + `screenshots/03-智能体真实LLM调用.png`
- `app/verify_local.mjs`：本机已接入版验证（mock，不花钱）

### 真实调用结果
- HTTP 200 / `deepseek-chat` / 4 步确定性工作流 /
  工具 `list_cold_exceeded → get_purchase → list_recalls → get_rule` /
  prompt 1315 + completion 505 = 1820 tokens / 徽标「LLM 增强模式」
- 答案结构化列出 4 条冷链超限记录并逐条带 `[T1]…[T4]` 依据；
  证据里同时保留离线确定性版本答案，可对照

### 实测驱动的修复
- LLM 答案里的 `**加粗**` 改为渲染 `<strong>`，不再露出星号
- 传给模型的工具明细原本只放前 3 行，模型诚实指出「观察命中 4 条、
  明细只有 3 条」→ 在 Prompt 里明确标注「以下为样例行、命中 N 条是完整数量」
  并新增第 6 条规则，重跑后答案干净
- 全流程复验：核心 `51/0`、浏览器 `12/0`、本机已接入版 `4/0`

### 安全核对
- 全仓库扫描：待提交文件**无真实密钥**；`evaluation_llm_real.json` 不含密钥；
  `app.local.html` 已被 gitignore
- 除用户授权的这次真实调用外，所有 LLM 验证使用 mock，未额外产生费用

### 文档
补齐清单第 1、2 项标为已完成，新增真实调用实测一节。

## [1.1.0] - 2026-11-19

**补上「没接 LLM」这个短板：生成层做成可插拔适配器，并新增选型适配评估。**

### 新增
- `app/app.html` 新增「LLM 接入设置」模块：填自己的 Base URL / Key / Model（OpenAI 兼容）
  即可启用；密钥只存本机 localStorage，不进仓库、不上传，默认关闭、可一键清除
- `polishWithLLM()`：LLM **只把已取到的工具结果组织成自然语言**；工具选择、意图路由、
  护栏、引用校验始终由代码决定；拒答不调用模型；失败/超时/欠费自动回退确定性模板
- 智能体页显示当前模式徽标，可一键对照「确定性版本」
- 项目成熟度评估：正面回答「是不是 Demo / 没接 LLM 够不够」，
  场景适配判断、7 条关键追问口径、按性价比的补齐清单

### 技术验证
- 实测服务商预检 `200`、放行 `authorization,content-type`、`Origin: null`（file://）被接受
  → 浏览器可直连，**不需要代理或后端**
- `node verify_app.mjs`：**PASS 51 / FAIL 0**（新增 8 项：默认离线、启用切换、
  LLM 组织答案、保留确定性版本、轨迹不变、拒答不调用、失败回退、清除密钥）
- `node verify_app_browser.mjs`：**PASS 12 / FAIL 0**（新增 4 项：设置页渲染、
  浏览器内 mock LLM 增强路径生效、徽标切换、清除恢复离线）
- 所有 LLM 验证使用 mock，**未调用真实模型、未产生费用**

### 结论
仍是 Demo（离线快照、不写库不上链、无用户系统），这点在评估文档里主动承认；
但对 AI 交付/实施/解决方案/产品岗够用，只有投大模型算法岗才不够。

## [1.0.0] - 2026-11-19

**完整业务系统交互软件 + AI 智能体模块（单文件离线，双击即用）。**

用户反馈「问答功能太简单，要把之前做的系统整成软件，AI 作为其中一个模块」。
按此重做：不再是单独的问答页，而是整套系统的可交互产品。

### 新增
- `app/app.html`：**158 KB 单文件交互软件**，内嵌真实业务数据快照
  （26 供应商 / 71 采购 / 41 加工 / 52 来源关系 / 16 销售 / 10 冷链 / 7 异常 /
  3 召回 / 186 存证事件），无 CDN / 无 fetch / 无外链
- **10 个业务模块可真实操作**：新增供应商/采购（证号必填、黑名单拦截）、
  验收（不合格必填原因）、多来源加工投料（余量/产出/重复引用校验）、
  上架与销售（条件扣减、售罄）、冷链（超限自动开工单）、异常处置与召回
  （联动销售置 RECALLED）、存证重试、公开溯源（检验结论与核验分栏）
- **AI 溯源智能体模块**：10 个工具、两种执行模式（单步调用 / 确定性工作流）、
  逐步可见的 Thought → Tool(args) → Observation 轨迹、答案附 `[Tn]` 依据、
  越界与无依据拒答；业务系统以确定性工作流为主，不放任 ReAct 乱逛
- `app/app.template.html` / `build_app.py` / `data/snapshot.json`：模板数据分离，
  优先读快照，重建**零依赖**（不需要 MySQL / pymysql）
- `app/verify_app.mjs` / `verify_app_browser.mjs`：核心逻辑与真实浏览器交互验证
- `app/README.md`：模块清单、智能体设计、9 步演示流程、已知边界

### 验证
- `node verify_app.mjs`：**PASS 43 / FAIL 0**（数据完整性 10 / 业务规则 19 /
  智能体轨迹 14）
- `node verify_app_browser.mjs`：**PASS 8 / FAIL 0**（Edge CDP 真实点击：
  登录 → 10 个模块 → 采购表格 41 行 → 智能体 4 步轨迹 → 切换模块）
- 修复一处真实缺陷：两段 `<script>` 共享全局词法环境，UI 脚本重复 `let S`
  导致整个界面脚本不执行；删掉重复声明后浏览器验证全过
- 页面无外部资源；数据行不含 `</script>` / `<!--`

### 说明
离线演示版：业务动作只改浏览器 localStorage，不写真实 MySQL、不上链；
数据为导出快照，核验状态不实时重算。真实系统仍以 Java 后端为准。

## [0.9.0] - 2026-11-19

**单文件离线产品页：不用环境、不用命令，双击即可演示。**

### 新增
- `trusted-rag/product.html`：**114.9 KB 单文件交互式产品**，内嵌 186 条链核验语料 +
  4 条规则 + 40 条评测集，无 CDN / 无 fetch / 无外链，断网可用
- `trusted-rag/product.template.html` / `build_product.py`：模板与数据分离，语料更新可重生成
- `trusted-rag/verify_product.mjs`：抽取页面真实运行的脚本，与 Python 版做一致性验证

### 能力
- 现场输入问题或点示例 → 实时返回带 `[n]` 引用的答案（引用含 eventId / verifyStatus / score）
- 六类拒答即时显示拦截原因
- 一键运行 40 条评测，浏览器内实时计算通过率
- 内置「30 秒上手流程」

### 验证
- `node verify_product.mjs`：**8 项指标与 Python 版完全一致**（40 条全过、失败用例无）
- Edge 无头浏览器 `file://` 实测：JS 正常渲染，答案与引用卡片可见
- 数据行不含 `</script>` / `<!--`，页面无外部资源引用

## [0.8.0] - 2026-11-19

**给零基础用户的运行入口（本轮所有命令均在本机实跑验证）。**

### 新增
- `docs/product/08-小白运行指南.md`：三条路线（A AI 子项目 / B 后端+前端 /
  C 完整含链）、怎么打开终端、每条命令的成功标志、10 条常见问题、术语脚注
- `scripts/doctor.py`：零依赖环境体检，检查 5 个工具 / 3 个服务 / 3 个构建产物，
  最后直接输出「你现在能跑什么」与下一步命令

### 实测记录
- 体检：9 项 OK / 2 项 MISS（虚拟机未开），路线 A、B 可跑，C 需先开虚拟机
- 路线 A：`python demo.py` 40 条全过；`python compare.py` 输出 v1/v2 对比
- 路线 B：后端登录 `code=0`（token 171 字节）、Vite 首页 HTTP 200、
  经前端代理登录 `code=0`；验证用进程已全部关闭，8080/5173 已释放
- **实测发现**：虚拟机未开时公开溯源接口会等链查询超时（5 秒超时），
  页面可能转圈几秒才显示黄色 `UNREACHABLE`。已在指南与体检脚本里写明「不是卡死」

### 说明
参考 GitHub 上 doctor.sh / setup-check 的通行做法（先逐项体检再端到端 smoke test），
但复用仓库已有 `smoke_test.py`，不引入任何新依赖或一键安装脚本。

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
- `TECH-08` v1.1（执行环境、四场景、重点用例、三组性能明细、D-01~D-04 缺陷表、结论）
- `TECH-10` §6 改成带状态的 7 处核对表；`TECH-11` 关闭 G6 / G7、阶段 E 打勾
- `TECH-01`（架构图层名 / 选型表 / 拓扑补 `eth_call :8545`）、`TECH-06`（配置块逐字段对齐 `application-dev.yml`）
- `README`：功能表、路线图、测试段、AI 模块取舍说明、文档索引同步

### 说明

- **AI 问答模块不做**是有意取舍：本期范围不含这一项，把时间花在核心链路（存证闭环、
  四类核验、幂等与冲突）的真实链上证据更值。按「设计了、未接入」如实标注
- TC-33 的篡改只针对本轮新建的独立测试批次，**测完已复原**（`overall` 回到 `PASS`），
  正式演示记录未被修改
- 性能采样是常规单用户交互响应，**不是压测**，不能当成并发能力结论

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
- 值得记录的实现难点：① 销售必须"先扣后判"，先查再改在中午高峰必然卖超；
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
- 值得记录的实现难点：三条并发坑（撞号 / S 锁死锁 / 快照读旧值），都是"代码看着对、压测就露"的类型

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
- 值得记录的实现难点：① eventId 字节数不一致 ② JSON 列与截断响应 ③ 中文路径下 surefire fork 失败（`mvn test -DforkCount=0`）

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
- 产品文档：需求规格说明书、用例说明、部署演示指南、演示操作手册、快速开始、AI 问答功能说明
- 技术文档：架构总览、数据库设计、合约设计、接口文档、环境版本清单、配置说明、AI 模块设计
- 测试文档：测试用例表（38 项设计稿）、测试记录（模板与执行约定）
- **合约源码**：`contract/TraceRegistry.sol`（追加式存证 + 幂等/冲突分支）
- **建表脚本**：`db/migration/V1__init.sql`（11 张表，含外键与 CHECK 约束）
- 设计基线冻结：10 张业务表、9 类存证事件、SHA-256 规范化摘要规范、四类核验状态机

### 说明
- 本版本含设计文档与可执行契约（合约、DDL），业务代码按 `docs/tech/` 中冻结的口径分阶段实现
- 后端/前端工程骨架在 P0–P1 阶段填充
