# SynPharm Deploy

智互药研（SynPharm）AI 药物研发平台 —— **部署与文档仓库**。

统一收纳四端 Docker 编排、一键拉取脚本与项目文档（架构设计 / API / 部署手册）。SynPharm 采用四仓库分离结构，本仓库是它们协同工作的枢纽。

## 仓库全景

```
synpharm-workspace/                 ← 约定：4 仓库 clone 到同一父目录
├── synpharm-frontend/              # Vue 3 + Vite 前端
├── synpharm-backend/               # Spring Boot 业务后端（含 sql/）
├── synpharm-fastapi/               # FastAPI 算法引擎（含 models/ 权重）
└── synpharm-deploy/                # 本仓库：编排 + 文档 + 脚本
```

| 仓库 | 地址 |
|------|------|
| synpharm-frontend | https://github.com/Ljz4777/synpharm-frontend |
| synpharm-backend | https://github.com/Ljz4777/synpharm-backend |
| synpharm-fastapi | https://github.com/Ljz4777/synpharm-fastapi |
| synpharm-deploy | https://github.com/Ljz4777/synpharm-deploy |

## 一键拉取全部仓库

```bash
# Windows
scripts\clone-all.bat

# Linux / macOS
./scripts/clone-all.sh
```

默认拉取到 `./synpharm-workspace/`，可传参自定义父目录与 Git 地址前缀：

```bash
./scripts/clone-all.sh ~/work git@github.com:Ljz4777
```

## 两套编排

### 开发环境：`docker-compose.dev.yml`（源码挂载 + 热重载）

| 端 | 运行方式 | 热重载机制 |
|----|----------|-----------|
| 后端 | `maven:3.9.6-eclipse-temurin-17` 镜像挂载源码跑 `spring-boot:run` | spring-boot-devtools 监听 `target/classes`，IDE 编译即重启；5005 端口可远程调试 |
| 算法 | 构建镜像挂载源码跑 `uvicorn --reload` | 保存 `.py` 即重启 |
| 前端 | `node:20-alpine` 挂载源码跑 Vite Dev Server | HMR，`/api` 经 Vite 代理到后端 |

启动：

```bash
cd synpharm-workspace
cp synpharm-deploy/.env.example .env     # 首次：编辑密码与密钥
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml up -d
```

> FastAPI 镜像含 PyTorch（约 2.3 GB），首次构建较慢；构建过一次后 `synpharm/fastapi:dev` 会被复用。

### 生产环境：`docker-compose.yml`（纯镜像引用）

compose 文件不含 `build`，完全解耦。需先在四仓共同父目录构建镜像：

```bash
docker build -t synpharm/backend:1.0.0  synpharm-backend
docker build -t synpharm/fastapi:1.0.0  synpharm-fastapi
docker build -t synpharm/frontend:1.0.0 synpharm-frontend

cp synpharm-deploy/.env.example .env     # 编辑强随机密钥
docker compose --env-file .env -f synpharm-deploy/docker-compose.yml up -d
```

版本通过 `.env` 中 `SYNPHARM_VERSION` 统一控制。

## 端口分配（开发环境默认值）

| 服务 | 宿主机端口 | 说明 |
|------|-----------|------|
| 前端 Vite | 5174 | 开发入口 |
| 后端 API | 7000 | Knife4j 文档 `/doc.html` |
| 后端调试 | 5005 | IDE remote attach |
| FastAPI | 18050 | Swagger `/docs` |
| MySQL | 13307 | 避开 Windows 保留端口段 |
| Redis | 6380 | 避开本机 6379 冲突 |
| RabbitMQ 管理台 | 15672 | 仅内网 |

## 环境变量

所有密码与密钥从 `.env` 读取（模板见 [.env.example](.env.example)），严禁硬编码。生产必改：`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`JWT_SECRET`（≥32 字节强随机）、`RABBITMQ_PASSWORD`。

生成强随机密钥：`openssl rand -hex 32`

## 项目文档

[docs/](docs/) 目录（随本仓库分发）：

- `docs/architecture/` — 架构设计与序列图
- `docs/api/` — 接口契约
- `docs/modules/` — 各端模块详细设计
- `docs/deploy/` — 部署与排障手册
- `docs/development/` — 开发规范

## 常用命令

```bash
# 查看服务状态
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml ps

# 跟随某服务日志
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml logs -f backend

# 停止并清理（保留数据卷）
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml down

# 连数据一起清理（危险）
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml down -v
```
