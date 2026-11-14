# 第一次 PR：基线数据自举与评价链路补全

| 项 | 内容 |
|:--|:--|
| 仓库 | `github.com/iMerryi/synpharm-workspace` |
| 分支 | `main`（本地与跟踪分支一致） |
| 提交 | `acbee94` · `76c9e7e` · `7b7ecac`（业务改动）<br>+ 部署变量补齐与本文档归档（`main`） |
| 时间 | 2026-11-11 ~ 2026-11-12 |
| 规模 | **18 个业务文件，+1242 / −24 行**；另 `.env.example` / `docker-compose.yml` 10 处、复盘文档 1 份 |
| 对应问题 | 用户视角 7 项中的 **6 项** |
| 前置文档 | `SynPharm_开发文档_基线数据自举与空数据防护.md` |

---

## 1. 一句话概括

**把「系统能打开、但什么都点不动」变成「能建项目、能看引擎通没通、能自己换分子看 3D」。**

---

## 2. 起点：接手时的真实状态

以普通用户身份走一遍，连续撞到六堵墙：

| # | 现象 | 用户感受 |
|:--:|:--|:--|
| 1 | 库页面三个数据集全是 `0 条`，部分标「待开放」 | 像没做完 |
| 2 | 新建项目时「分子形式」「主靶点」两个下拉是空的 | 根本建不了项目 |
| 3 | 分子精修台项目下拉为空 | 整页无法开始 |
| 4 | 评价工作台信息多但看不懂 | 不知道 DTI/PPI/DDI 通没通 |
| 5 | 评价记录报「依赖的下游服务暂不可用」 | 功能像坏了 |
| 6 | 3D 可视化只能看一个分子 | 最好玩的模块用不了 |

前 3 个是**同一条因果链**：

```
MySQL 初始化脚本只在数据卷为空时执行
  → 37 / 48 / 49 三个种子脚本从未在已有环境跑过
  → design_library_item 与 target 表为空
  → 两个下拉为空 → 创建按钮永久禁用
  → 项目数 0 → 精修台无项目可选
```

---

## 3. 做了什么

### 3.1 让数据能进去（`acbee94` + `7b7ecac`）

| 文件 | 作用 |
|:--|:--|
| `synpharm-deploy/sql/51_schema_seed_log.sql` | 新增执行登记表（`seed_code` + `checksum` + `rows_affected` + `applied_by`） |
| `synpharm-deploy/scripts/apply-seeds.sh` | 幂等执行器：按 `37 → 48 → 49` 顺序执行，跑完回读校验再登记 |
| `.../design/support/SeedDataHealthIndicator.java` | 启动期探针，缺数据时在 `/actuator/health` 暴露 `missing` 明细 |
| `.../support/SeedDataHealthIndicatorTest.java` | 覆盖 UP / UNKNOWN / 异常三种状态 |
| `synpharm-deploy/README.md` | 补「已有数据卷如何补齐基线数据」一节 |

**为什么不是简单写个 shell 循环：**

- 只按「跑过没」判断 → 种子文件改了不会重跑
- 只按「数据在不在」判断 → 部分失败时无法续跑
- 因此用 `seed_code + checksum`：脚本变了自动重跑，没变则 `SKIP`

### 3.2 让「坏掉」能被看见（`acbee94`）

| 改动 | 原来 | 现在 |
|:--|:--|:--|
| `Projects.vue` | 下拉空，按钮灰着，没有原因 | 表单顶部提示「字典未加载」并给出下一步 |
| `Library.vue` | 空库显示「待开放」（像功能没做） | 显示「暂无数据」+ 种子未导入的说明 |
| `RecordsPanel.vue` | 直接甩「下游服务不可用」 | 提示先查 predict 服务是否启动 |
| `nginx.conf` | 默认只放行 1MB | `client_max_body_size 100m`，与后端上限对齐 |
| `EmailCaptchaServiceImpl.java` | 验证码 key 用原样邮箱，大写 QQ 邮箱必失败 | 统一 `trim + toLowerCase` 归一化 |

### 3.3 让评价链路可用（`76c9e7e`）

| 文件 | 作用 |
|:--|:--|
| `api/platform.ts`（新增） | 接上 `GET /api/platform/capabilities` 与 `/algorithm-health` |
| `EngineStatusBar.vue`（新增 269 行） | 三个状态胶囊：DTI / PPI / DDI；附算法引擎在线状态、延迟、探测时间、手动刷新 |
| `OverviewPanel.vue` | 概览页加上手卡片，先讲「这是什么、从哪开始」 |
| `Visualization.vue` | 同页结果选择器，可在历史评价结果间切换，不再只认 URL 里那一条 |
| `synpharm-deploy/docker-compose.yml` | **补上遗漏的 `predict` 服务**（这是评价记录 503 的真正原因，随 `acbee94` 提交） |
| `PredictPanel.vue` / `BoundaryPanel.vue` / `EvaluationSystem.vue` | 接入状态条，调整提示 |

---

## 4. 四个关键取舍

| 决策 | 选择 | 理由 | 放弃的方案 |
|:--|:--|:--|:--|
| 种子执行机制 | 登记表 + `checksum` + 独立脚本 | 与现有手工执行习惯一致，能进 CI，可续跑 | 引入 Flyway / Liquibase：要重排 60+ 脚本，回归面太大 |
| 缺数据时的健康状态 | `UNKNOWN`（HTTP 200） | `DOWN` 会让 `/actuator/health` 返回 503 → docker healthcheck 判死 → core 重启循环 → **用户连登录页都打不开** | 直接 `DOWN`：能报警，但会自断服务 |
| 引擎状态的实现 | 复用已有 `/capabilities` | 后端早就返回 `models.{dti,ppi,ddi}`，只是前端没调用；零后端改动 | 新建一个 `/engine/status` 接口 |
| 3D 换分子 | 同页下拉选择器 | 改动小、不打断浏览、符合「结果详情页」的定位 | 新增独立路由 `/visualization/:id` |

---

## 5. 完成度对照

### 5.1 对用户 6 个问题

| # | 问题 | 状态 | 说明 |
|:--:|:--|:--:|:--|
| 1 | 库是空的 | ✅ 已修 | 种子补齐 + 空态文案改为「暂无数据」 |
| 2 | 建不了项目 | ✅ 已修 | 分子形式 7 项、靶点 21 条已可读（现场实测值，以数据库为准） |
| 3 | 精修台选不到项目 | ✅ 已修 | 级联恢复（页面原有引导条，无需新增） |
| 4 | 评价工作台看不懂 | ✅ 已修 | 引擎状态条 + 概览上手卡片 |
| 5 | 评价记录 503 | ✅ 已修 | 补 `predict` 服务 + 错误提示可操作 |
| 6 | 3D 只能看一个 | ✅ 已修 | 同页结果选择器 |
| **1b** | **无法建立私有库** | ❌ **未做** | 功能本身未开发，按钮写死 `disabled`；需新增写入接口，约 3.5 天 |

**7 项中 6 项完成，1 项（私有库）明确留到下一批。**

### 5.2 对开发文档 8 步

| 步骤 | 状态 |
|:--:|:--:|
| 1 手工救火 | ✅ |
| 2 登记表 `51_schema_seed_log.sql` | ✅（编号由 50 改为 51，50 已被占用） |
| 3 `apply-seeds.sh` + `--dry-run` | ✅ |
| 4 `--force` 跑一次，再普通跑一次 | ✅（实际执行中发现并修掉 `No database selected`；二次执行三条全部 `SKIP`） |
| 5 健康指标 + 单测 | ✅ |
| 6 人为删数据做负向验证 | ⚠️ 无留存记录，建议补一次 |
| 7 前端两处空态 | ✅ |
| 8 更新 README / 问题台账 | ✅（README 已更新；`未修复功能清单.md` 待同步） |

---

## 6. 验证记录

**有证据的（2026-11-12 补测）：**

- `apply-seeds.sh` 在运行中的 MySQL 上实际执行过：首次 37/48 成功、49 报 `No database selected`，定位为手工执行未带默认库，已补 `-D synpharm_core`（`7b7ecac`）；修复后二次执行三条全部 `SKIP`
- 执行后数据库实测：`library_dataset=3`、`target=21`、`library_item=50`（分子 19 / 抗体 10）、`MODALITY=7`、`ASSAY=8`，三条种子均已写入 `schema_seed_log`
- 后端编译与单测（JDK 26 + `-Dmaven.compiler.proc=full`）：`synpharm-core` / `synpharm-auth` / `synpharm-platform` 全部 `BUILD SUCCESS`；`SeedDataHealthIndicatorTest` 3/3 通过
- 前端类型检查与构建（Node 22）：`vue-tsc --noEmit` 0 错误，`vite build` 成功（1m17s，仅 chunk 体积警告）
- 网关链路（真实游客 token）：`/api/tasks`、`/api/design/projects`、`/api/library/datasets`、`/api/platform/capabilities`、`/api/results` 均返回 200
- 线上公共库 API 返回：靶点 21 / 分子 19 / 抗体 10

**未验证 / 待补：**

| 项 | 状态 |
|:--|:--|
| 三个真实引擎（DTI/PPI/DDI）连通 | 未验证，状态条只反映接口返回 |
| 负向验证（删数据看提示） | 未留存记录 |
| 新环境从零起全栈 | 未跑过 |

**这一点必须如实写：** 状态条能显示「没通」，但不能证明「通了就真能算」。后者需要真实模型权重。

**工作区备注：** `synpharm-frontend/src/views/Projects.vue` 有未提交改动，但 `git diff --ignore-all-space` 结果为空，属于行尾/空白差异，无实质内容，本次提交已排除。

---

## 7. 未完成与下一步

| 优先级 | 事项 | 预估 |
|:--:|:--|--:|
| P1 | 私有库写入（建库 / 导入 / 编辑 / 删除） | 3.5 天 |
| P1 | CSV 批量导入（前端 + 后端 + 错误行回报） | 2 天 |
| P2 | 真实数据接入（UniProt / PDB / ChEMBL 按 C1–C5 清洗） | 周级，**依赖课题组原始文件** |
| P2 | 负向验证与前端构建纳入 CI | 0.5 天 |

---

## 8. 怎么复现验收

```bash
# 1. 补齐基线数据（已有数据卷）
MYSQL_ROOT_PASSWORD=*** bash synpharm-deploy/scripts/apply-seeds.sh

# 2. 二次执行应全部 SKIP
MYSQL_ROOT_PASSWORD=*** bash synpharm-deploy/scripts/apply-seeds.sh

# 3. 数据断言
docker exec -i synpharm-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
SELECT 'datasets' k, COUNT(*) v FROM synpharm_core.library_dataset WHERE deleted=0
UNION ALL SELECT 'items',    COUNT(*) FROM synpharm_core.library_item WHERE deleted=0
UNION ALL SELECT 'targets',  COUNT(*) FROM synpharm_core.target WHERE deleted=0
UNION ALL SELECT 'modality', COUNT(*) FROM synpharm_core.design_library_item WHERE category='MODALITY' AND deleted=0;"

# 4. 健康检查应出现 seedData
curl -s localhost:8083/actuator/health | grep -o '"seedData"[^}]*}'
```

**界面验收：** 新建项目两个下拉有值 → 能建项目 → 评价体系顶部三个胶囊有颜色 → 3D 页面能切换分子。

---

## 9. 踩过的坑（留给下一个人）

1. **MySQL 官方镜像只在数据卷为空时执行 `sql/`**。已部署环境升级后，新加的种子脚本永远不会自动跑——这是本次所有空数据问题的总根源。
2. **`37_design_seed_data.sql` 会跨库写 `synpharm_platform.design_tool`**，必须用 root 执行；用业务账号会在这一行报 `ERROR 1142` 中断，**后面的指标种子也不会写**，表现为「表对了一半」。
3. **`49_library_mock.sql` 用了临时表**，必须单会话执行（`mysql < file`），用 GUI 逐条跑一定失败。
4. **手工执行 `mysql` 要带 `-D`**，否则报 `No database selected`——官方镜像的 entrypoint 帮忙选了库，手工没有。
5. **健康检查不能返回 `DOWN`**，否则 docker healthcheck 会把 core 判死，用户连登录页都看不到，也就永远看不到你写的那条提示。
6. **`sql/50` 已被 `50_candidate_prediction.sql` 占用**，本次登记表只能用 `51`。新加脚本前先 `ls sql/ | tail` 看最大编号，别直接接 50。
7. **本机 `git fetch` 报 `CRYPT_E_NO_REVOCATION_CHECK`**（Windows schannel 吊销检查失败），推送前若遇同样报错，可临时设 `git config http.schannelCheckRevoke false` 再操作，完成后改回。

---

## 10. 这份改动的价值

- **对用户**：系统从「点不动」变成「能走完一条完整设计流程」。
- **对团队**：新环境不再依赖某个人记得手工跑 SQL，一条命令可复现。
- **对答辩**：可以讲清「我们发现了什么根因、怎么设计防御、怎么验证」，而不是只展示页面。
- **对后续**：登记表 + 健康探针是基础设施，后面接真实数据集时可以直接复用同一套执行与校验机制。

---

## 11. 补充：部署编排的 `.env` 变量缺口（2026-11-12）

| 项 | 内容 |
|:--|:--|
| 分支 | `fix/deploy-env-vars`（已推送） |
| 提交 | `294f5e1` |
| 规模 | **2 个文件，+14 / −5 行** |
| 范围 | 仅 `synpharm-deploy/`，不动业务代码 |

### 11.1 现象

按 README 执行 `cp .env.example .env` 后跑 `docker compose up -d`，一条变量都插值不出来，直接中断：

```
error while interpolating services.auth.environment.AUTH_DB_PASSWORD:
required variable AUTH_DB_PASSWORD is missing a value: 请在 .env 中设置 AUTH_DB_PASSWORD
```

auth / core / nacos / platform / predict 五个服务轮流报同类错误。

### 11.2 根因（三层，逐层叠出来的）

| 层 | 问题 | 为什么致命 |
|:--:|:--|:--|
| 1 | 模板里的密钥类变量全是空值 | compose 的 `${VAR:?}` 对**空字符串**同样报 missing a value，不是「有键就行」 |
| 2 | `.env.example` 从来没有 `CORE_DB_*` | `git log -S CORE_DB_PASSWORD -- .env.example` 全程零命中；而 compose 的 `core` 服务用 `${CORE_DB_PASSWORD:?}` 强校验 → 照模板复制必然缺 |
| 3 | `.env.example` 还留着 `DESIGN_DB_*` | 2026-10-22 设计域账号已并入 core（schema 更名 `synpharm_core`），残留会让人以为该填 DESIGN 而不是 CORE |

`core` 服务与 `CORE_DB_*` **不是后来改出来的**：唯一带 `.git` 的仓库 `synpharm-repo` 里，根提交 `c187080`（初始导入 v1.31.0 快照）就已包含 `core` 服务与 `CORE_DB_PASSWORD`。**是上游模板漏同步，不是本地改坏。**

### 11.3 改了什么

| 文件 | 改动 |
|:--|:--|
| `synpharm-deploy/.env.example` | 删掉过期 `DESIGN_DB_*` 段，改为 `CORE_DB_USER` / `CORE_DB_PASSWORD`，并注明「已并入 core、旧键勿用」 |
| `synpharm-deploy/docker-compose.yml` | `mysql` 服务 environment 补 `ASSET_DB_USER` / `ASSET_DB_PASSWORD` / `PLATFORM_DB_USER` / `PLATFORM_DB_PASSWORD`；同时修正「2026-10-22 三个账号合并为 core」这条已不成立的注释（asset / platform 已于 2026-11-04 拆出） |

**为什么键名必须和 `sql/00_create_accounts.sh` 严格对齐：** 该脚本是数据卷为空时唯一的建号入口，读的就是 `AUTH_/CORE_/ASSET_/PLATFORM_/PREDICT_` + `_DB_USER` / `_DB_PASSWORD`。键名差一个前缀，脚本不报错，只打印「跳过 xx：未提供账号/口令」然后什么都不建。

### 11.4 漏传 `ASSET_DB_*` / `PLATFORM_DB_*` 的隐患

`mysql` 服务原来只传了 AUTH / CORE / PREDICT 三组。已有数据卷时看不出任何问题（账号早就建好了），但**全新部署**会这样：

```
MySQL 数据卷为空 → 00_create_accounts.sh 执行
  → ASSET_DB_PASSWORD / PLATFORM_DB_PASSWORD 为空 → 脚本静默跳过
  → asset / platform 容器起来后连库失败：账号不存在
  → 网关 /api/platform/**、/internal/files/** 全线 503
```

这类问题在已有环境上永远不会自己暴露，只有「换台机器从零部署」才踩到，所以必须在模板层面修掉。

### 11.5 关键取舍：回填现有口令，而不是重新生成

| 方案 | 做法 | 结果 |
|:--|:--|:--|
| A（采用） | 从运行中容器读出已在用的口令，回填 `.env` | 服务无感，`up -d` 后照旧连库 |
| B（放弃） | 全部用 `openssl rand` 重新生成 | 现有数据卷里的账号口令没变，容器拿到新口令 → 五个服务全部鉴权失败 |

MySQL 官方镜像只在**数据卷为空时**执行建号脚本，之后改 `.env` 里的口令不会同步到库中账号（除非手工 `ALTER USER`）。所以对已部署环境，正确做法是「把已在用的值补进本地 `.env`」，而不是「生成一批新的」。全新部署时再由使用者按模板注释自行生成。

### 11.6 验证记录

| 项 | 结果 |
|:--|:--|
| `docker compose config --quiet` | 退出码 0（修前是 `CORE_DB_PASSWORD` 插值报错） |
| compose 中全部 13 个 `${VAR:?}` 必需变量 | 全部非空 |
| 用 `.env` 口令实测登录 MySQL | `synpharm_auth / core / asset / platform / predict` 五个账号全部 OK |
| 仓库副本与运行副本 compose | 内容逐行一致（仅换行符不同：仓库 CRLF，运行副本 LF） |
| `.env` 编码 | UTF-8 无 BOM + CRLF，注释与键顺序未动，仅新增 `CORE_DB_*` 两键 |

真实口令一律不进文档、不进 Git；`.env` 已被 `**/.env` 忽略。

### 11.7 本地验收命令

```bash
cd synpharm-deploy
# 已部署环境：不要覆盖现有 .env，只把空值补齐
cp .env.example .env
# 按模板注释生成：数据库口令 openssl rand -hex 16
#                 INTERNAL_TOKEN  openssl rand -hex 24
#                 NACOS_AUTH_TOKEN openssl rand -base64 48
docker compose config --quiet && echo "插值通过"
docker compose up -d
```

### 11.8 遗留

- 本次改动已推送到 `origin`（`git push -u origin fix/deploy-env-vars`），远端分支与本地 commit 一致；可直接开 PR：`https://github.com/iMerryi/synpharm-workspace/pull/new/fix/deploy-env-vars`
- 本机访问 GitHub 的 HTTPS 需要走本地代理（`127.0.0.1:7890`），Git 默认不读系统代理；该仓库已用 `git config --local http.proxy / https.proxy` 配好，仅对本仓库生效
- 运行副本的 `.env` 是按现有数据卷回填的本机私密文件，不同步、不提交
- 补充（2026-11-12）：本机 `synpharm-repo` 之前只把这些改动留在工作区、并未落到 `main`；本次已与文档一起提交到 `main`，避免「分支有、主线没有」的漂移

---

## 12. 运行副本漂移导致的游客登录 401（2026-11-12）

### 12.1 现象

点「游客体验 / 游客模式」后能拿到 token，但进入任意页面立刻被踢回登录页，所有需要登录的接口返回：

```json
{"code":40100,"errorCode":"UNAUTHORIZED","message":"未登录或登录已失效","data":null}
```

### 12.2 定位过程

| 步骤 | 结果 |
|:--|:--|
| 直接调 `POST /api/auth/login {"loginType":"guest"}` | 200，正常签发 token |
| 用该 token 调 `/api/tasks` 等接口 | 全部 401 |
| 校验 token 签名 | 与 `JWT_SECRET` 完全匹配（HS512），**不是密钥不一致** |
| 查看网关日志 | 早期确有 `Token 签名校验失败`，但新 token 不再产生该日志 |
| 对比容器环境变量 | **运行中的 gateway 没有 `INTERNAL_TOKEN`**，而 core/auth/platform/predict 都有 |

### 12.3 根因

当前运行的编排来自旧解压副本：

```
C:\Users\HONOR\Desktop\SynParm\synpharm-workspace-main\synpharm-workspace-main\synpharm-deploy
```

它的 `gateway` 服务环境变量漏了 `INTERNAL_TOKEN`：

- 网关验签通过后注入 `X-User-Id` / `X-User-Role`，但不会盖 `X-Internal-Token`
- core 的 `InternalRequestFilter` 只信任「内部令牌正确」的身份头，否则一律按匿名处理
- 业务接口 `CurrentUser.requireUserId()` 于是抛 401
- 前端 `request.ts` 拦截器收到 401 → `logout()` → 跳登录页

仓库 `synpharm-repo/synpharm-deploy/docker-compose.yml:220` **本来就有** 这项，属于运行副本落后于仓库，不是本次代码改动引入。

### 12.4 处理

1. 在运行副本的 compose 的 `gateway.environment` 补 `INTERNAL_TOKEN: ${INTERNAL_TOKEN:?...}`
2. `docker compose --project-name synpharm --project-directory <运行副本>/synpharm-deploy up -d --force-recreate --no-deps gateway`
3. 用真实游客 token 复测

### 12.5 验证

| 接口 | 修复前 | 修复后 |
|:--|:--:|:--:|
| `POST /api/auth/login`（guest） | 200 | 200 |
| `/api/tasks` | 401 | 200 |
| `/api/design/projects` | 401 | 200（total=0，需手动建项目） |
| `/api/library/datasets` | 401 | 200（21 / 19 / 10） |
| `/api/platform/capabilities` | 401 | 200 |
| `/api/results` | 401 | 200 |

### 12.6 教训

1. **仓库 ≠ 运行副本**：部署目录必须与仓库 compose 做 diff，尤其是 `environment` 段；这次两处缺口（缺 `predict`、缺 `INTERNAL_TOKEN`）都出在运行副本。
2. **JWT 签名通过 ≠ 请求被信任**：网关之后的内部令牌是第二道门，排查 401 时要把「验签」和「身份头可信」分开看。
3. 运行副本重建只解决当下；长期应统一用 `synpharm-repo` 发版，避免两边继续分叉。
