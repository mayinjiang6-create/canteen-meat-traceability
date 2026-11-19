# 部署与现场验收清单

> 配套：`README.md`（项目说明）、`evaluation.json`（评测报告）、`index.html`（静态演示页）
> 本文面向「客户现场 / 面试演示」场景：怎么起、怎么验、坏了怎么查。

---

## 0 三种运行形态（按现场条件选）

| 形态 | 命令 | 依赖 | 现在的状态 |
|---|---|---|---|
| **静态演示页** | 打开在线链接 | 只要浏览器 | ✅ 已发布：`https://trusted-rag-trace.app.workbuddy.host/?sharecode=6TQLACPETO` |
| **本地 Python 服务** | `PORT=8000 python server.py` | Python 3.13 标准库 | ✅ 本机实测通过（health / ask / metrics 三接口） |
| **Docker 容器** | `docker compose up --build` | Docker 20.10+ / Compose 2.0+ | ⚠️ 配置已就绪，**本机未实测**（宿主机 Hyper-V 关闭，Docker Desktop 不可用） |

> 诚实说明：本机没有可用的 Docker 运行环境，所以容器化配置只做了结构与指令检查，
> 没声称"容器里跑过"。在支持 Docker 的机器上按下面步骤走即可；若失败，把日志发回来我接着修。

---

## 1 本地 Python 服务（最轻量，无 Docker）

```bash
cd trusted-rag
PORT=8000 python server.py
# 另开一个终端验收
curl http://127.0.0.1:8000/health
curl -X POST http://127.0.0.1:8000/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"PB20261118001 这批猪肉的检验结论是什么？"}'
curl http://127.0.0.1:8000/metrics
```

不需要 MySQL、不需要区块链节点、不需要 API Key、不需要 `pip install`。
语料 `data/events.json` 已导出自带链上核验结果，服务是**离线可跑**的。

---

## 2 Docker 容器（客户现场私有化部署）

```bash
cd trusted-rag
docker compose up --build -d
docker compose ps                 # 状态应为 Up (healthy)
curl http://localhost:8000/health
```

停止与清理：

```bash
docker compose down
```

**可选生成式模式**：在 `docker-compose.yml` 里取消 `LLM_API_KEY` 注释并填 Key，
或现场用环境变量注入。代码不存密钥；切换后同一个评测命令要重跑，
因为检索/生成链路变了，指标不能沿用离线版数字。

**资源**：镜像基于 `python:3.13-slim`，容器内只有标准库，无数据库、无向量库，
属于"单端口 HTTP 服务"，比动辄多个容器的知识库平台轻很多。
现场机器只要能跑 Docker、有 8000 端口即可。

---

## 3 现场验收清单（逐条打勾）

| # | 检查项 | 命令 / 操作 | 预期 |
|---|---|---|---|
| V1 | 服务健康 | `GET /health` | `status=ok`，`chunks=190`，`chainVerified=true` |
| V2 | 单批次事实问答 | `POST /ask` 问「PB20261118001 的检验结论」 | `grounded=true`，答案含"合格"，引用 1 条 |
| V3 | 流程规则问答 | 问「召回之后还能继续销售吗」 | 答案含"禁止继续销售"，引用指向规则文档 |
| V4 | 拒答：不存在的批次 | 问「PB29991111001 的检疫证号」 | `grounded=false`，「未在已核验记录中找到依据」 |
| V5 | 拒答：越界问题 | 问「这批肉吃了安全吗」 | `grounded=false`，「该问题超出溯源记录范围」 |
| V6 | 引用完整性 | 看 `citations[0]` | 含 `eventId / batchId / verifyStatus / score` |
| V7 | 指标可复现 | `python demo.py` | 20/20，拒答正确率 1.00，生成 `evaluation.json` |
| V8 | 静态页可访问 | 打开在线链接 | 4 个示例问答卡片正常显示，指标卡数字与 V7 一致 |

> V4 / V5 是**必测**：一个只会答对的系统不可信，会拒答的系统才可信。

---

## 4 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `/health` 连不上 | 服务没起 / 端口占用 | `docker compose ps` 或检查 8000 端口；改 `PORT` 后重启 |
| `chunks` 远小于 190 | `data/events.json` 缺失或没 COPY 进镜像 | 确认目录完整后重新 `docker compose up --build` |
| `chainVerified=false` | 语料是降级导出的（当时链不可达） | 在能连链的机器上跑 `python build_corpus.py` 重建语料 |
| 回答总是拒答 | 阈值过高 / 问法太口语 | 先试带批次号的问法；调 `TrustedRAG(threshold=...)` 后**重跑评测** |
| 答案带引用但没回答所问 | 覆盖性校验未命中该字段 | 在 `FIELD_EVIDENCE` 里补字段模式，并补一条评测用例 |
| 容器 unhealthy | healthcheck 路径不通 | 进容器 `curl localhost:8000/health`；镜像内无 curl 时用文档里的 python 命令 |

---

## 5 给面试官的 30 秒部署说明

> 这个服务是单端口 HTTP，标准库实现，容器里没有数据库也没有向量库——
> 语料是离线导出时逐条查链核验过的，运行时不依赖区块链节点。
> 现场 `docker compose up` 就能起，健康检查和验收清单都写在仓库里。
> 之所以不做成多容器平台方案，是因为现场条件常常只有一台机器、一个端口；
> 容器化在这里解决的是"交付可复现"，不是堆组件。
