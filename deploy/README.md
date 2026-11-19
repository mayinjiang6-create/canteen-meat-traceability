# deploy · 客户现场交付包

三个容器一条命令起全栈：MySQL + 后端 + 前端（Nginx 托管页面并反代 `/api`）。
链（FISCO BCOS / WeBASE）是**可选外部依赖**，不接也能完整跑业务。

## 快速开始

```bash
cd deploy
cp .env.example .env                       # 改三个口令：MYSQL_ROOT_PASSWORD / DB_PASSWORD / JWT_SECRET
python check_deploy.py                     # 交付前静态自检（不需要 Docker）
docker compose up -d --build               # 首次 3–8 分钟
# http://<服务器IP>:8088    admin / Admin@123
```

## 这里有什么

| 路径 | 作用 |
|---|---|
| `docker-compose.yml` | 三服务编排：健康检查、依赖顺序、数据卷 |
| `.env.example` | 环境变量模板（`.env` 已被 gitignore） |
| `check_deploy.py` | 35 项一致性自检：端口 / 构建上下文 / jar 名 / 反代目标 / 环境变量闭环 |
| `dify/` | 同场景低代码平台对照：190 块知识库 + Prompt + 同口径打分脚本 |

## 完整作业手册

端口表、密钥注入、链可选与降级、备份恢复、升级回滚、**14 条验收 Checklist（客户签字用）**、
12 类故障排查 → [`docs/product/09-客户现场部署清单.md`](../docs/product/09-客户现场部署清单.md)

## 说明

- 本包的容器**未在本机实跑**（开发机 Hyper-V 已关闭以运行 VMware，Docker Desktop 不可用）；
  静态一致性已 35/35 通过，构建与运行验证需在有 Docker 的机器上执行
- 不要在 CI 里构建这个 compose：CI 只做 `docker compose config` 语法校验
