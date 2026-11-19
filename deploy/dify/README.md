# Dify 同场景对照 · 搭建清单

> 目的：在**同一个场景**（只回答链上核验过的溯源记录）上搭一个 Dify 版本，
> 用来回答面试必问的「为什么不用现成框架」。产物是**可复现的搭建步骤 + 同一份评测集的对照方法**，
> 不是"我们上了 Dify"。

## 0 先读这段（诚实边界）

- 本机 **没有 Docker Desktop**（Hyper-V 已关闭以运行 VMware），因此 **Dify 未在本机实跑**。
  下面每一步都是按 Dify 社区版官方文档写的可执行清单，**但"跑过"这句话我不写**。
- 面试口径：**「我搭过同场景的 PoC 设计并核对了平台的每个节点能做到什么；
> 本机没有 Docker 环境，所以没有实测数字。」** 不要临场说"我用过 Dify 跑了 80 条"。
- 对照结论（§4）是基于**平台能力**与**本项目约束**的逐条比对，属于设计分析，不是实测数据。

## 1 启动（3 条命令，2 核 4 GiB 起）

```bash
git clone https://github.com/langgenius/dify.git
cd dify/docker
cp .env.example .env
docker compose up -d          # 首次拉镜像约 8 个容器，5–10 分钟
# 打开 http://localhost/install 设置管理员账号
```

> 资源要求：官方最低 2 核 / 4 GiB。和本项目主栈（MySQL + 后端 + 前端）同机时
> 建议 8 GiB 以上，否则向量库与 API 容器会互相抢内存。

## 2 建知识库

1. 「知识库 → 创建 → 导入已有文本」，上传 `deploy/dify/kb_meat_trace.md`
2. 分段设置（关键，决定能不能对上批次）：
   - 分段标识符：**自定义 `## `**（一条事件一个块，190 块）
   - 最大分段长度：**800 tokens**，重叠 **0**
   - 预处理：关闭「自动清洗」（否则可能吃掉 `- 事件ID：…` 这些行）
3. 索引方式：
   - 有 Key → 「高质量」（向量检索）
   - 零成本验证 → 「经济」（关键词检索）。**本项目语料是精确批次号查询，经济模式反而更稳**
4. 检索测试：输入 `PB20261118001 验收`，确认 top1 命中 `PB20261118001 · ACCEPT_RESULT`，
   且块内含 `事件ID：6f1c…`

## 3 搭工作流（4 个节点）

```
开始 → 知识检索 → LLM → 直接回复
```

| 节点 | 关键配置 | 为什么 |
|---|---|---|
| 开始 | 输入变量 `query`（或直接用 `sys.query`） | — |
| 知识检索 | 关联上面的知识库；**Top K = 3**；**Score 阈值 = 0.5**；查询变量 = `sys.query` | 阈值是平台唯一能表达的"低分拒答" |
| LLM | 模型 `deepseek-chat`；温度 **0.1**；SYSTEM 用 `deploy/dify/prompt.md` | 低温减少发挥；约束全在 SYSTEM |
| 直接回复 | 输出 LLM 的 `text` | — |

可选加一个 **条件分支（IF/ELSE）** 在检索后：
`IF 检索结果条数 == 0 → 直接回复「未在已核验记录中找到依据」`，其余走 LLM。
这是平台里最接近"代码拦截"的做法，但只能拦"没检索到"，拦不住"检索到了却不该答"。

## 4 用同一份评测集对照（怎么测、测什么）

```bash
# 1) 导出与仓库完全一致的题面（只导出问题，不带期望，避免"喂答案"）
python - <<'PY'
import json, pathlib
rows = [json.loads(l) for l in pathlib.Path("trusted-rag/eval_set.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
pathlib.Path("deploy/dify/questions.txt").write_text("\n".join(r["question"] for r in rows) + "\n", encoding="utf-8")
PY

# 2) 在 Dify 里逐条提问（或用它的 API 批量跑），把答案贴回一个 jsonl：
#    {"id":"Q01","answer":"...","grounded":true/false}
# 3) 用与 demo.py 相同的判定口径统计
python deploy/dify/score_platform.py deploy/dify/answers_dify.jsonl
```

要看的四个数（与 `evaluation.json` 同口径）：

| 指标 | 含义 | 平台版最可能掉在哪 |
|---|---|---|
| 答案正确率 | 关键词命中 + 引用批次正确 | 引用没有结构化批次，只能靠文本里是否出现批次号 |
| **拒答正确率** | 该拒的都拒了 | **最大风险**：越界/无锚点/跨批次全靠 Prompt |
| 引用一致性 | 引用是否落在标注批次 | 平台返回 chunk 来源，需人工映射 |
| 平均延迟 | 端到端 | 含 LLM 生成，通常 1–3 s，比离线抽取式慢 3 个数量级 |

> 这一节的价值不在数字，而在**先定口径再测**：换平台不改评测口径，等于没测。

## 5 文件

| 文件 | 作用 |
|---|---|
| `kb_meat_trace.md` | 190 个块（186 事件 + 4 规则），每条带 eventId / 批次 / 核验状态 |
| `build_kb.py` | 从 `trusted-rag/data/events.json` 重新生成上面这份知识库 |
| `prompt.md` | 7 条硬约束 + 4 条对照样例 + 每条约束对应的代码实现 |
| `score_platform.py` | 用与 `demo.py` 相同的判定口径给平台答案打分 |
