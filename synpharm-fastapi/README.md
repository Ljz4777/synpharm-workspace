# SynPharm FastAPI

智互药研（SynPharm）AI 药物研发平台 —— **算法引擎仓库**。

基于 FastAPI 的 AI 推理服务，承载三大预测模型，供 Spring Boot 后端通过内网 HTTP 调用。服务不直接对公网暴露，通过 API Key 完成服务间认证。

## 模型一览

| 能力 | 模型 | 说明 |
|------|------|------|
| DTI（药物-靶点相互作用） | KAN-MoDTI | 输入药物 SMILES + 靶点蛋白序列 |
| DDI（药物-药物相互作用） | DDI-LLM | 输入两个药物的 SMILES |
| PPI（蛋白质-蛋白质相互作用） | FlashPPI | 输入两条蛋白序列 |

模型采用**懒加载**：首次请求对应接口时才加载权重，`/health/` 端点会报告各模型的权重存在性与加载状态。

## 技术栈

FastAPI · Uvicorn · PyTorch 2.5.1 (CPU) · Transformers · Pydantic Settings

## 目录结构

```
synpharm-fastapi/
├── api/v1/               # 路由：predict / ddi / health
├── core/                 # 认证（API Key）、异常处理、日志
├── services/             # 模型加载与推理逻辑
├── models/               # 模型权重（不入库，运行时挂载）
│   ├── KAN-MoDTI/weights/
│   ├── DDI-LLM/weights/
│   └── FlashPPI/weights/
├── config.py             # Pydantic Settings（环境变量驱动）
├── main.py               # 应用入口（lifespan 启动日志）
├── requirements.txt      # Python 依赖（torch 由 Dockerfile 单独安装）
└── Dockerfile            # 多阶段构建（CPU 版 PyTorch，非 root 运行）
```

## 快速开始

### 方式一：Docker 开发环境（推荐，全栈联调）

与本仓库同级的 `synpharm-deploy` 提供一键编排。引擎以 **uvicorn `--reload`** 运行，源码挂载，保存 `.py` 即自动重启：

```bash
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml up -d fastapi
# API 文档: http://localhost:18050/docs
# 健康检查: http://localhost:18050/health/
```

### 方式二：本地独立运行

```bash
pip install -r requirements.txt
pip install "torch==2.5.1+cpu" --find-links https://mirrors.aliyun.com/pytorch-wheels/cpu/
uvicorn main:app --reload --port 8000
```

## 模型权重

权重文件**不纳入 Git 管理**，需下载后放置到 `models/<模型名>/weights/` 下：

| 模型 | 期望路径 |
|------|----------|
| KAN-MoDTI | `models/KAN-MoDTI/weights/human_final.pth` |
| DDI-LLM | `models/DDI-LLM/weights/ddi_gcn_morgan.pt` |
| FlashPPI | `models/FlashPPI/weights/model.safetensors` |

Docker 部署时 `models/` 以只读卷挂载进容器（`MODEL_DIR=/app/models`）。

## 环境变量

| 变量 | 说明 | 默认 |
|------|------|------|
| `MODEL_DIR` | 模型权重根目录 | `/app/models`（容器）/ `./models`（本地） |
| `DEVICE` | 推理设备 `cpu` / `cuda:0` | `cpu` |
| `BATCH_SIZE` | 批量推理大小 | `50` |
| `API_KEYS` | 合法 API Key 列表（逗号分隔）；留空则关闭认证（仅限内网） | 空 |
| `LOG_LEVEL` | 日志级别 | `INFO` |

## API 认证

除 `/health` 外，所有接口需携带请求头 `X-API-Key: <key>`。后端服务通过 `FASTAPI_API_KEY` 注入同一个 Key 完成服务间认证。交互式文档见 `/docs`（Swagger UI）。

## 构建与部署

```bash
docker build -t synpharm/fastapi:1.0.0 .
```

> 说明：Dockerfile 固定安装 `torch==2.5.1+cpu`——transformers 5.x 要求 torch ≥ 2.5；而 torch 2.6 起 `weights_only=True` 默认值会导致整包 pickle 的 DTI/DDI 权重加载失败，故上限锁定 2.5.x。

生产部署使用 `synpharm-deploy/docker-compose.yml`（纯镜像引用）。

## 相关仓库

| 仓库 | 说明 |
|------|------|
| [synpharm-backend](https://github.com/Ljz4777/synpharm-backend) | Spring Boot 业务后端（本服务的唯一调用方） |
| [synpharm-frontend](https://github.com/Ljz4777/synpharm-frontend) | Vue 3 前端 |
| [synpharm-deploy](https://github.com/Ljz4777/synpharm-deploy) | Docker 编排、一键脚本与项目文档 |
