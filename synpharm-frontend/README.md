# SynPharm Frontend

智互药研（SynPharm）AI 药物研发平台 —— **前端仓库**。

基于 Vue 3 + Vite + TypeScript 的 PC 端 Web 应用，提供 DTI（药物-靶点相互作用）、DDI（药物-药物相互作用）、PPI（蛋白质-蛋白质相互作用）三大预测能力的可视化工作台，内置 2D 分子编辑器（Ketcher）与 3D 分子可视化（MolStar）。

## 技术栈

| 分类 | 选型 |
|------|------|
| 框架 | Vue 3（Composition API）+ Vue Router 4 |
| 构建 | Vite 5 + TypeScript |
| UI | Element Plus + SCSS |
| 状态管理 | Pinia |
| 图表 | ECharts 5 |
| 分子编辑器 | Ketcher + Indigo（独立子应用，构建产物至 `public/ketcher`） |
| 3D 可视化 | MolStar + Three.js |
| HTTP | Axios |

## 目录结构

```
synpharm-frontend/
├── src/                  # 主应用源码
│   ├── api/              # 后端接口封装（Axios）
│   ├── views/            # 页面（DTI / DDI / PPI 工作台等）
│   ├── stores/           # Pinia 状态
│   └── styles/           # 全局样式与变量
├── ketcher-app/          # 2D 分子编辑器子应用（独立 vite 构建）
├── nginx.conf            # 生产 Nginx 配置（SPA + /api 反代）
├── Dockerfile            # 多阶段构建（Node 构建 → nginx-unprivileged 运行）
└── vite.config.ts        # dev server（含 /api 代理）
```

## 快速开始

### 方式一：Docker 开发环境（推荐，全栈联调）

与本仓库同级的 `synpharm-deploy` 提供一键编排，前端以 **Vite Dev Server + HMR** 运行，源码挂载，保存即热更新：

```bash
# 在 4 仓库的共同父目录下（首次先执行 synpharm-deploy/scripts/clone-all）
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml up -d frontend
# 访问 http://localhost:5174
```

此模式下 `/api` 请求由 Vite 代理到容器网络内的后端（`VITE_PROXY_TARGET=http://backend:8080`）。

### 方式二：本地独立运行（仅前端，连本机后端）

```bash
npm install
npm run dev        # http://localhost:5174
```

默认将 `/api` 代理到 `http://localhost:7000`；如需指向其他后端地址：

```bash
# Windows PowerShell
$env:VITE_PROXY_TARGET="http://localhost:8080"; npm run dev
```

## 环境变量

| 变量 | 说明 | 默认 |
|------|------|------|
| `VITE_PROXY_TARGET` | dev server 的 `/api` 代理目标 | `http://localhost:7000` |
| `VITE_API_BASE_URL` | 生产构建时的 API 基础路径（同源部署留空，走 Nginx 反代） | 空 |
| `VITE_ENABLE_MOCK` | 是否启用本地 mock 数据（便于初级用户体验演示） | `false` |

## 构建与部署

```bash
npm run build      # 先构建 ketcher 子应用，再 vue-tsc 类型检查 + vite 构建主应用
```

生产镜像（在仓库根目录执行）：

```bash
docker build -t synpharm/frontend:1.0.0 .
```

生产部署使用 `synpharm-deploy/docker-compose.yml`，容器内 Nginx 监听 8080，`/api` 反代至后端服务。

## 相关仓库

| 仓库 | 说明 |
|------|------|
| [synpharm-backend](https://github.com/Ljz4777/synpharm-backend) | Spring Boot 业务 API（本前端的调用对象） |
| [synpharm-fastapi](https://github.com/Ljz4777/synpharm-fastapi) | FastAPI 算法引擎（由后端转发调用） |
| [synpharm-deploy](https://github.com/Ljz4777/synpharm-deploy) | Docker 编排、一键脚本与项目文档 |

> 接口契约：前端类型由后端 Knife4j 导出的 OpenAPI 生成（`openapi-typescript`），联调时请以后端 `/v3/api-docs` 为准。
