# SynPharm · AI 药物研发平台

从靶点到候选的一体化药物研发工作台：**需求基线 → 候选生成 → 评估与编辑 → 决策** 四步各自留下
可回放的快照与指纹，另有独立的分子预测产品线（PPI / DTI / DDI 与三维结构可视化）。

整个项目围绕一条底线设计：**算不出来就说算不出来**。指标缺失时留空并如实告知覆盖率、判定不了
标「待判定」、证据不全的候选进不了决策表 —— 不用估算值或默认值冒充结论。

---

## 目录结构

| 目录 | 内容 |
|---|---|
| [`synpharm-frontend`](./synpharm-frontend) | Vue 3 + Vite 前端。二维分子画布（Ketcher + Indigo 实算）、Mol\* 结构渲染、设计流程与决策各页面 |
| [`synpharm-cloud`](./synpharm-cloud) | Spring Cloud 微服务。`gateway` / `auth` / `core` / `predict` 四个服务，`core` 承载主业务域 |
| [`synpharm-fastapi`](./synpharm-fastapi) | 算法引擎。理化性质实算、PPI / DTI / DDI 预测 |
| [`synpharm-deploy`](./synpharm-deploy) | Docker Compose 编排、数据库初始化脚本（`sql/`）、架构与设计文档（`docs/`） |
| `e2e-*.ps1` | 端到端验证脚本，每个对应一条业务链路（见下文） |

各子项目的详细说明在各自目录的 `README.md`；架构与三阶段设计文档在
[`synpharm-deploy/docs`](./synpharm-deploy/docs)。

---

## 快速开始

### 1. 准备环境变量

**仓库里不含任何真实口令**，所有 `.env` 都被忽略，只提供模板。首次运行需要自己生成：

```bash
cd synpharm-deploy
cp .env.example .env
# 然后按注释填写：数据库口令、JWT 密钥、内部令牌、算法引擎 API Key
# 随机值可用：openssl rand -hex 32
```

### 2. 构建分子编辑器子应用（必须）

二维画布的化学引擎是独立构建的第三方产物，约 30 MB，**不入库**：

```bash
cd synpharm-frontend
npm install
npm run build:ketcher     # 生成 public/ketcher/，缺它画布会白屏
```

### 3. 启动整套服务

```bash
cd synpharm-deploy
docker compose -f docker-compose.dev.yml up -d
```

首次启动会自动创建各服务独立数据库账号与 schema，并执行 `sql/` 下的初始化脚本。

### 4. 访问

| 入口 | 地址 |
|---|---|
| 前端 | http://localhost:5174 |
| 网关 | http://localhost:8080 |
| Nacos 控制台 | http://localhost:18848 |

未注册时可用**游客身份**进入，功能完整、数据归属自己；项目列表点「一键体验」会自动跑完
建项目 → 冻结基线 → 生成候选 → 派生一版 → 生成决策。

---

## 业务主线

```
需求分析      八块基线（靶点/结构/分子形式/硬约束/软约束/指标/配额/风险）
   ↓          不齐不允许冻结；冻结后的快照是后续唯一输入
候选生成      去重 → 粗筛 → 多样性配额，逐分子留下结构化原因
   ↓
评估与编辑    按版本冻结分数 → 诊断缺口 → 匹配动作 → 评估影响面
   ↓
决策          证据装配 → 硬约束复核 → 业务否决 → 分桶排序 → 名额分配 → 确认与回放
```

### 三条不可绕过的规则

1. **证据先于判断** —— 没有冻结证据包的候选进不了决策表。
2. **否决不可绕过** —— BLOCK 级否决与硬约束不达标只能改数据后重新决策，不能在决策层手动改成「入选」。
3. **确认即不可改** —— 确认后任何去向都不能再改，要改就新建一版并指向上一版，历史只增不删。

---

## 端到端验证

每个脚本都用真实接口跑完整链路，打印 `PASS / FAIL` 与最终 `RESULT pass=N fail=0`：

```powershell
.\e2e-canvas-candidate.ps1     # 二维画布 → 候选 → 评估冻结 → 进决策
.\e2e-candidate-version.ps1    # 画布改结构 → 版本树新增一版 → 重新评估
.\e2e-decision.ps1             # 决策 D0~D8：证据/否决/分桶/名额/调整/风险接受/确认/回放
.\e2e-decision-ui.ps1          # 决策页每个取数与写操作
.\e2e-baseline.ps1             # 需求基线八块与冻结
.\e2e-generation.ps1           # 生成漏斗与逐分子去向
.\e2e-edit.ps1                 # 缺口诊断 / 动作匹配 / 影响面 / 版本树
```

后端单元测试：

```bash
cd synpharm-cloud && mvn -o -pl synpharm-core test      # 440 个用例
```

---

## 安全说明

- **口令不入库**：仓库根与 `synpharm-deploy/` 的 `.env` 均被 `.gitignore` 排除，
  只保留 `*.env.example` 模板。克隆后请自行生成新值，不要复用示例。
- **构建产物不入库**：`node_modules/`、`target/`、`public/ketcher/`（约 30 MB 化学引擎）、
  `dist/` 全部排除，按上文步骤重建。
- **本地备份不入库**：`_synpharm-*.zip` / `_synpharm-*.sql` 是迁移前的本地安全副本，含数据库转储。

---

## 已知边界

- 序列类分子（抗体 / 多肽）：计算层目前只提供小分子理化指标，画布与版本写回也仅适用于小分子。
- 自动编辑动作（按缺口推荐「加取代基」并自动生成结构）依赖 `crem` 引擎，尚未部署；
  当前是人在画布上改、系统负责把结果合法地记进版本树。
- 阶段 7（湿实验结果回流）与阶段 8（真编辑引擎接入）尚未开始。
