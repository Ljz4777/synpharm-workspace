# SynPharm Cloud · 微服务骨架

SpringCloud 微服务侧的工程根目录。进度见 [`docs/architecture/SpringCloud微服务重构方案.md`](../synpharm-deploy/docs/architecture/SpringCloud微服务重构方案.md)
§11：**阶段 0/1/2A/2B/2C、3、4、5 均已完成**（auth / core / predict / asset / platform 全部在跑，网关 4 条路由）；主业务域（阶段 6：需求分析 → 生成 → 编辑 → 评估 → 决策）已推进到**决策 E2~E5**（装配置信包 → 否决复核 → 分桶 → 名额），阶段 7（湿实验回流）/ 8（真编辑引擎）未开始。**进度以本文末尾的版本表为准**（§11.4 的端点计数是阶段 5 之前的旧口径）。

> 单体 `synpharm-backend` 曾是唯一的后端，如今**已退役**：auth（阶段 1）、
> predict（阶段 4）、`/api/system/**`（阶段 4 顺带）相继迁出，其 Controller 全部删净。
> 2026-10-16 单体零端点后停止编排，**2026-10-19 源码目录也已从工作区删除**
> （备份见工作区根目录 `_synpharm-backend-final-backup-20261019.zip`）。
> 数据库初始化脚本早已迁至 `synpharm-deploy/sql/`，删除源码目录不影响 initdb。
>
> 除 `synpharm-auth`、`synpharm-predict`（都是从单体抽出来的）外，其余服务均为**白纸域**：
> 单体从未实现过，表与端点是新建的。

---

## 1. 目录结构

```
synpharm-cloud/
├── pom.xml                     父工程（BoM + 版本 + 编译配置）
├── Dockerfile                  网关镜像（多阶段，构建上下文是本目录）
├── maven-settings.xml          阿里云镜像（与 synpharm-backend 一致）
├── synpharm-common/            公共库
├── synpharm-gateway/           边缘网关（唯一鉴权点）
├── synpharm-auth/              身份与访问（阶段 1 从单体抽出）
├── synpharm-predict/           预测域（阶段 4 代码已迁入，待切流）
└── synpharm-core/               主业务（design+asset+platform 合并，2026-10-22）
```

### 模块清单与阶段进度

| 模块 | 阶段 | 拥有的表 / 职责 |
|---|---|---|
| `synpharm-common` | 0 | 响应信封、单一 `ErrorCode` 枚举、内部透传头、服务名、Redis 键约定；**阶段 2C 起**收敛各服务重复的共享设施（可信身份与内部令牌过滤器、归属校验、全局异常处理器、分页信封、JSON 编解码、MyBatis-Plus 分页插件） |
| `synpharm-gateway` | 0 | 路由、JWT 验签、身份头注入、内部令牌、追踪 ID |
| `synpharm-auth` | 1 | `sys_user`、`sys_login_log`（schema `synpharm_auth`）；注册/登录/验证码/个人中心/登录审计 |
| `synpharm-core` | 2A/2B 起 | **主业务 41 张表**（schema `synpharm_core`）= 原 design 19（项目/靶点/约束/生成/候选/编辑/评估/报告/湿实验）+ asset 1（`file_asset`）+ platform 1（`design_tool`）+ 需求分析 3（`baseline_snapshot` / `project_metric` / `project_risk_item`）+ 决策 6（`evidence_bundle` / `decision_veto_rule` / `decision_record` / `decision_candidate` / `decision_veto_hit` / `risk_acceptance`）+ 生成 5（`generation_slice` / `generation_candidate_log` / `generation_cache` / `dedup_merge` / `generation_output_snapshot`）+ 编辑 6（`evaluation_freeze` / `edit_action_mapping` / `edit_gap_report` / `edit_impact_assessment` / `edit_batch` / `edit_delta_report`）。**2026-10-22 服务收敛**：design / asset / platform 三者合并为一个服务（8083），网关 4 条路由（auth / core / predict / core-system）。已接入 RabbitMQ（`synpharm.exchange` + `design.*` + `design.dlq`）；`ToolRegistryService` 的 `@Scheduled` 10min 能力探测与 `GET /api/system/algorithm-health` 同在本服务。单测 **440**（决策域 36 个） |
| `synpharm-predict` | 4 | 预测域 **10 张表** —— 全部归本服务所有（`predict_task` / `predict_result` / `user_favorite` / `batch_task` / `batch_task_item` / `ddi_supported_drug` / `agent_session` / `agent_step` / `agent_report` / `tool_invocation_log`），schema `synpharm_predict`。**代码 87 个类已迁入并切流**：网关把 `/api/predict/**`、`/api/results/**`、`/api/favorites/**`、`/api/tasks/**`、`/api/batch/**` 指向本服务，**单体侧代码与 `synpharm` 侧旧表均已删除**。单测 **59** 个（8 个测试类随代码一并迁入，全部通过）。`agent_*` 三张表属二期，代码尚未实现 |

> 镜像构建用 `MODULE` 参数选择服务，一份 Dockerfile 覆盖全部服务（`SERVER_PORT` 按端口分配表传）：
> ```powershell
> docker build -t synpharm/gateway:1.0.0 synpharm-cloud
> docker build -t synpharm/auth:1.0.0     --build-arg MODULE=synpharm-auth     --build-arg SERVER_PORT=8081 synpharm-cloud
> docker build -t synpharm/predict:1.0.0  --build-arg MODULE=synpharm-predict  --build-arg SERVER_PORT=8082 synpharm-cloud
> docker build -t synpharm/core:1.0.0    --build-arg MODULE=synpharm-core    --build-arg SERVER_PORT=8083 synpharm-cloud
> ```
> ⚠ 必须把**每一个**模块的 `pom.xml` 都加进 Dockerfile 的 COPY 列表：Maven 读父 POM 的
> `<modules>` 会逐个校验目录存在，缺一个就报 `Child module ... does not exist`，整个构建失败。
> 阶段 1 加 `synpharm-auth` 时踩过一次；阶段 3/4/5 新增 `asset`/`platform`/`predict` 后 COPY 列表
> **再次漏掉这三个 pom**（已修）。此后每次改父 POM 的 `<modules>` 都要同步该列表。

---

## 2. 技术栈版本

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | **3.3.5** | 与单体 `synpharm-backend` 完全一致 |
| Spring Cloud | **2023.0.3** | Leyton 列车。**这是能用的最新版**，上界受 gateway 限制，见下方「版本上界」 |
| Spring Cloud Alibaba | **2023.0.3.4** | 其父 POM 为 `spring-cloud-dependencies-parent:4.1.0`，与 2023.0.3 同属早期 4.1.x |
| Nacos Server | **v2.4.3** | 与 Alibaba 内置的 `nacos-client 2.4.3` 对齐 |
| JDK | **17** | 与单体一致 |

### ⚠️ 版本上界：为什么不能用 Spring Cloud 2023.0.4+

Spring Cloud Gateway **4.1.6 起**在 `RemoveHopByHopHeadersFilter` 里调用了
`HttpHeaders.headerSet()` —— 这是 **Spring Framework 6.2** 才有的方法，
而 Spring Boot 3.3.5 带的是 Framework **6.1.14**。结果不是启动失败，而是**运行期**抛
`java.lang.NoSuchMethodError`，表现为网关所有转发请求 500 / 连接被重置。

| Spring Cloud | Gateway | 与 Boot 3.3.5 |
|---|---|---|
| **2023.0.3** | 4.1.5 | ✅ 可用 |
| 2023.0.4 | 4.1.6 | ❌ 需要 Framework 6.2 |
| 2023.0.5 | 4.1.6 | ❌ 同上 |
| 2023.0.6 | 4.1.9 | ❌ 同上 |

> 升级前请先验一次，别只看版本号：
> ```powershell
> curl.exe -sSLo gw.jar https://maven.aliyun.com/repository/public/org/springframework/cloud/spring-cloud-gateway-server/<ver>/spring-cloud-gateway-server-<ver>.jar
> javap -c -classpath gw.jar org.springframework.cloud.gateway.filter.headers.RemoveHopByHopHeadersFilter | Select-String headerSet
> ```
> 无输出 = 可用；出现 `HttpHeaders.headerSet` = 需要 Framework 6.2。
>
> 要上 2023.0.4+，必须同时把 Spring Boot 升到 3.4.x（Framework 6.2）——那是另一个独立变更，
> 且会牵动单体，需单独评估。

---

## 3. 快速开始

### 3.1 编排环境（推荐）

```powershell
cd d:\synpharm-workspace
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml up -d `
  mysql redis rabbitmq nacos backend gateway
```

| 入口 | 地址 |
|---|---|
| **网关（唯一外部入口）** | http://localhost:8080 |
| Nacos 控制台 | http://localhost:18848/nacos （`nacos` / `nacos`） |
| 后端直连（绕过网关排查用） | http://localhost:18081 |

### 3.2 本机直跑（不动容器）

```powershell
# 1) 只把基础设施放容器里
docker compose --env-file .env -f synpharm-deploy/docker-compose.dev.yml up -d mysql redis rabbitmq nacos

# 2) 网关（要点：先 install 父工程与 synpharm-common，再进入模块 run）
cd d:\synpharm-workspace\synpharm-cloud
mvn -B -DskipTests install
cd synpharm-gateway
mvn -B spring-boot:run "-Dspring-boot.run.jvmArguments=-DNACOS_SERVER_ADDR=localhost:18848 -DNACOS_DISCOVERY_ENABLED=true -DJWT_SECRET=<见 .env>"
```

> 本机直跑时 `mysql` 请连 `localhost:${MYSQL_PORT}`（见 `.env`），Redis 连 `localhost:6380`。

---

## 4. 网关做了什么

| 能力 | 实现 | 阶段 0 的开关 |
|---|---|---|
| **路由** | `/api/auth/**`、`/api/users/**` → auth；`/api/predict/**` → predict；`/api/design/**` → design（含 `platform-design-tools` 前置）；`/api/system/**` → platform；**无兜底路由** | 固定 |
| **剥离伪造身份头** | 无条件移除客户端传入的 `X-User-Id` / `X-User-Role` / `X-User-Email` / `X-Token-Id` / `X-Internal-Token` | 固定启用 |
| **JWT 验签 + 身份头注入** | `IdentityHeaderGlobalFilter` + `JwtVerifier`（HS256，与单体同密钥同 claim） | 固定启用 |
| **Token 黑名单** | 读 Redis `token:blacklist:{jti}`（与单体同 DB） | 固定启用 |
| **是否拦截** | `synpharm.gateway.auth.enforce` | **`false`** |
| **追踪 ID** | `X-Request-Id` / `X-Trace-Id` 生成与回写 | 固定启用 |
| **CORS** | `synpharm.gateway.cors.enabled` | **`false`**（避免与下游重复响应头） |
| **限流** | 未实现 | 阶段 5 用 Sentinel |

### 为什么阶段 0 不拦截（`enforce=false`）

单体目前**自己**验签 Token（`JwtAuthenticationFilter`）。若网关同时拦截，两处的公开接口清单、
错误码、401 语义都可能不一致，风险远大于收益。所以阶段 0 只做两件**不可能改变行为**的事：

1. 剥离伪造的身份头 —— 单体本来就不读这些头（它从 Token 里取），因此行为不变；
2. 给带有效 Token 的请求注入身份头 —— 单体同样不读，因此行为不变。

等到阶段 1 抽走 auth，才把 `enforce` 改成 `true`、`forward-authorization` 改成 `false`，
网关成为唯一鉴权点。

### 当前路由表（阶段 1 / 2）

```yaml
routes:
  # auth 已独立，必须排在兜底路由之前（Spring Cloud Gateway 按声明顺序匹配）
  - id: auth
    uri: lb://synpharm-auth
    predicates: [ Path=/api/auth/**,/api/users/** ]
  # 2026-10-22 服务收敛：design + asset + platform 合并为 synpharm-core。
  # 原先 platform-design-tools 必须排在 design 之前的隐式依赖随之消失。
  # 所以整段前缀可以直接切过来，未实现的端点会得到干净的 404 而不是单体兜底 500
  - id: core
    uri: lb://synpharm-core
    predicates: [ Path=/api/design/** ]
  # 尚未迁走的路径不再有兜底路由：网关直接返回带信封的 404 RESOURCE_NOT_FOUND
```

后续阶段按方案 §10.2 同样在兜底路由前插入。

> ⚠ SSE 端点 `/api/design/stream/{jobNo}` **尚未实现**。实现时需为它单独建一条路由，
> 并加 `RemoveResponseHeader=Transfer-Encoding`，否则网关会缓冲整段响应、SSE 退化成一次性返回。

---

## 5. 加入一个新服务模块

1. 在父 `pom.xml` 的 `<modules>` 里加一行；
2. 新建模块，`<parent>` 指向 `synpharm-cloud`，依赖 `synpharm-common`；
   启动类用 `@SpringBootApplication(scanBasePackages = "com.synpharm")` —— 否则公共库里的
   内部身份过滤器 / 全局异常处理器 / `MybatisPlusConfig` 不会被装配；
3. `spring.application.name` 必须等于 [`ServiceNames`](synpharm-common/src/main/java/com/synpharm/common/constant/ServiceNames.java)
   里登记的常量（网关的 `lb://` 直接用它）；
4. 在网关路由表**兜底路由之前**插入该服务的前缀路由；
5. 在 `docker-compose.dev.yml` / `docker-compose.yml` 里加服务；
6. 在该服务的 schema 上授予独立账号权限，**不要**建跨服务外键。

---

## 6. synpharm-common 的定位

**栈中立**：公共库必须同时服务 servlet 栈的业务服务（auth / design / asset…）与 reactive 栈的网关。
servlet 与 MyBatis-Plus 相关的 starter 因此一律声明为 **`provided`**：本模块编译与测试可见，
**不向下游传递** —— 否则 `spring-boot-starter-web` 会经 `synpharm-common` 进入 gateway 的 classpath，
Spring MVC 与 Spring Cloud Gateway 并存会让网关直接启动失败。

> 装配约定：业务服务用 `@SpringBootApplication(scanBasePackages = "com.synpharm")` 才能扫描到
> 公共库里的 `@Component` / `@Configuration`。`SynpharmInternalProperties` 标注 `@Component`
> 以保证注册（它不在业务服务 `@ConfigurationPropertiesScan` 的默认扫描包内）。

提供：

| 类 | 用途 |
|---|---|
| [`Result<T>`](synpharm-common/src/main/java/com/synpharm/common/api/Result.java) | 统一响应信封（`code` / `errorCode` / `message` / `data` / `warnings`） |
| [`Warning`](synpharm-common/src/main/java/com/synpharm/common/api/Warning.java) | 部分成功 / 能力降级提示（勘误 C-04） |
| [`ErrorCode`](synpharm-common/src/main/java/com/synpharm/common/error/ErrorCode.java) | **单一**错误码枚举：通用 00~09 / auth 10~19 / design 40~59（含 `code = httpStatus × 100 + seq` 的推导） |
| [`BizException`](synpharm-common/src/main/java/com/synpharm/common/exception/BizException.java) | 业务异常 |
| [`InternalHeaders`](synpharm-common/src/main/java/com/synpharm/common/constant/InternalHeaders.java) | 服务间透传头 |
| [`ServiceNames`](synpharm-common/src/main/java/com/synpharm/common/constant/ServiceNames.java) | 服务注册名 |
| [`RedisKeys`](synpharm-common/src/main/java/com/synpharm/common/constant/RedisKeys.java) | Redis 键与 DB 编号约定 |
| [`CurrentUser`](synpharm-common/src/main/java/com/synpharm/common/security/CurrentUser.java) | 当前请求的可信身份（ThreadLocal，由过滤器写入，仅来源可信时非空） |
| [`InternalRequestFilter`](synpharm-common/src/main/java/com/synpharm/common/security/InternalRequestFilter.java) | 服务边界守门人：`X-Internal-Token` 匹配才信任身份头，否则一律匿名（fail-closed） |
| [`SynpharmInternalProperties`](synpharm-common/src/main/java/com/synpharm/common/security/SynpharmInternalProperties.java) | `synpharm.internal.*` 配置（网关注入身份头用的共享口令） |
| [`OwnershipGuard`](synpharm-common/src/main/java/com/synpharm/common/security/OwnershipGuard.java) | 归属校验（失败一律 404，不泄漏「资源存在但不属于你」） |
| [`GlobalExceptionHandler`](synpharm-common/src/main/java/com/synpharm/common/web/GlobalExceptionHandler.java) | 全局异常 → 统一信封 + 正确 HTTP 状态行（400 / 404 / 405 / 409 / 500） |
| [`PageResult<T>`](synpharm-common/src/main/java/com/synpharm/common/web/PageResult.java) | 偏移分页信封 `{list,total,page,pageSize,hasNext}` |
| [`JsonCodec`](synpharm-common/src/main/java/com/synpharm/common/support/JsonCodec.java) | 数据库 `json` 列 ↔ Java 对象（编码失败直接 500，不静默写 null） |
| [`MybatisPlusConfig`](synpharm-common/src/main/java/com/synpharm/common/mybatis/MybatisPlusConfig.java) | MyBatis-Plus 分页插件（`maxLimit=200`，不 overflow） |

### ⚠️ `Result.success()` 返回 `code = 0`，不是 `200`

规范（勘误 C-01）把成功码定义为 `0`，但单体 `com.synpharm.utils.Result` 仍返回 `200`，
前端 `src/utils/request.ts` 也按 `code !== 200` 判错。因此：

| 场景 | 现在能不能用 `Result.success()` |
|---|---|
| 网关自身产生的**错误**响应（HTTP 非 2xx） | ✅ 可以 |
| 会被当前前端直接消费的**业务**接口 | ❌ **不行** —— 前端会判为失败 |

必须先把勘误 C-01 落到单体和前端（改动量：前端 2 行 + 后端 2 个工厂方法），
这一步做在抽 auth 的阶段 1。

---

## 7. 排障

| 现象 | 原因 / 处理 |
|---|---|
| 网关返回 `503`，日志 `Unable to find instance for synpharm-xxx` | 对应服务没注册上。检查其 `NACOS_DISCOVERY_ENABLED=true` 与 `NACOS_SERVER_ADDR`（单体已退役，此报错不再指向 `synpharm-backend`） |
| 启动报 `JWT 密钥长度不足` | `JWT_SECRET` 缺失或不足 32 字节。网关与 auth 必须用**同一个** `JWT_SECRET`（fail-fast 是刻意设计） |
| 启动报 `spring.config.import must contain a nacos: entry` | 类路径上有 nacos-config 但没写 import。本工程已在默认 profile 设 `import-check.enabled=false`，如果你新增模块请照抄 |
| 运行期 `NoSuchMethodError: HttpHeaders.headerSet()` | Spring Cloud ≥2023.0.4 的 gateway 4.1.6+ 需要 Framework 6.2，而 Boot 3.3.5 带的是 6.1.14。**只能用 2023.0.3**，见 §2 |
| 网关日志 `Token 黑名单查询失败` | Redis 不可达。此时网关**不会**静默放行：`enforce=false` 下原样转发（下游仍会 fail-closed 再判一次），`enforce=true` 下直接 `503 DEPENDENCY_UNAVAILABLE` |
| 容器绑定端口报 `access a socket in a way forbidden` | 落在 Windows 的 Hyper-V/WSL 保留端口段。查：`netsh interface ipv4 show excludedportrange protocol=tcp`，改用 15000 以上的端口 |
| 登录仍是 401 但网关日志显示验签通过 | `enforce=false` 时网关不拦截，401 来自单体自身；检查两边 `JWT_SECRET` 是否一致 |
| 改了前端却**看不到任何变化**（样式没生效、新按钮不出现） | **Windows 下 Docker bind mount 不传递文件变更通知**（inotify 不可靠），Vite dev server 启动后不会重新加载。`vue-tsc` 0 错、`vite build` 通过、模块请求 200 **都发现不了**。判定：`docker exec synpharm-dev-frontend wc -c /app/src/xxx.vue` 与 `Invoke-WebRequest http://localhost:$FRONTEND_DEV_PORT/src/xxx.vue`（本机当前 15174） 的字节数是否对得上；处理：`docker restart synpharm-dev-frontend`（20~40s 就绪），**每次改前端都要重启并核对字节数** |
| 中文写进库变成 `?` 或 `å°åˆ†å­` | 两回事：`?` 是 PowerShell `Invoke-RestMethod` 按系统代码页编码 JSON 造成的（**不可逆**，只能用 `.sql` 文件 + `--default-character-set=utf8mb4` 重造数据）；`å°åˆ†å­` 是历史上「UTF-8 被当 latin1 再存一次」的双重编码（重跑对应种子脚本可修） |
| 分子画布（Ketcher）白屏 / iframe 里出现了整个主应用 | `public/ketcher/` 产物不存在。Vite 的 SPA fallback 对缺失路径**返回 index.html 而非 404**，于是 iframe 把主应用自己装了进去。处理：`npm run build:ketcher`（需 Node 20+，产物约 29 MB Indigo 引擎，落在 `public/ketcher/`），然后重启 dev server —— `public/` 里新增目录不会被已启动的 dev server 识别 |
| Maven/PowerShell 向无 `@RequestBody` 的 POST 传 `$null` 报 `Content-Type 'application/x-www-form-urlencoded' is not supported` | PowerShell 5.1 的 `Invoke-WebRequest -Method POST` 在没有 Body 时默认带 form-urlencoded 头，Spring 拒绝。写 E2E 时这类 POST 传 `@{}` 而不是 `$null`（前端 axios 不带 Content-Type，不受影响） |
| 想检测「中文被双重编码」却写成 `WHERE col LIKE '%å%'` | **危险**：utf8mb4 默认排序规则 `utf8mb4_0900_ai_ci` 下 `'å' LIKE 'a'` 为真，条件会命中**所有含英文字母的字符串**，于是把正确的中文也做一次 latin1 转换，结果变成不可逆的 `?`。正确判定两条同时成立：① `latin1→binary→utf8mb4` 还原后与原值**字节不同**；② 还原结果**不含 `?`**（正确中文做这个转换会退化成 `?????`）。需要按字节定位时用 `LOCATE(UNHEX('C3A5'), CAST(col AS BINARY))` |

---

## 8. 待打通：预测域 → 设计流程（方案，**未实现**）

现状是两条互不引用的产品线：

| | 产出 | 被谁消费 |
|---|---|---|
| 分子预测（predict） | `prediction_result`：DTI / PPI / DDI 的亲和力、置信度、概率 | 只有前端的预测中心、结果页、任务页 |
| 设计流程（core） | 基线快照 → 候选 → 评估冻结 → 决策 | 只读自己的表 |

**为什么值得打通**：评估冻结的 `weight_coverage` 目前只有 **33.3%** —— 小分子 10 个种子指标里只有 QED / LIPINSKI_PASS / PAINS_HITS / BRENK_HITS 能算（权重合计 0.35 / 1.05）。剩下 6 个里有 4 个的语义**本来就是预测域的产物**：

| 指标码 | 权重 | 应该来自 |
|---|---|---|
| `DTI_CONFIDENCE` | 0.30 | DTI 预测的置信度 |
| `SELECTIVITY_INDEX` | 0.10 | 靶点 vs 反靶点的 DTI 差值 |
| `VINA_AFFINITY` | 0.10 | 对接打分（需要口袋，即基线块2） |
| `DDI_PROBABILITY` | 0.05 | DDI 预测概率 |

按当前种子权重，这 4 个能回填的话覆盖率从 33.3% 升到 **85.7%**（剩 `SA_SCORE` 0.10、`MAX_SIMILARITY` 0.05 仍需别的来源）。

### 最小闭环（三步）

1. **取数**：core 加内部端点读 predict 的既有结果，例如
   `GET /internal/prediction?targetId=&smiles=&type=DTI`，返回 `{value, realness, runId}`。
   注意 predict 侧已有真实推理，不需要新模型。
2. **口径**：新增映射表 `metric_prediction_source`（`metricCode` → `predictType` + 取值字段 + 单位换算 + `realness` 来源）。
   放在表里而不是代码里，因为「哪个指标用哪次预测」是会变的业务口径，且必须可解释（红线 R1）。
3. **回填时机**：只在**评估冻结**（`EvaluationFreezeAppServiceImpl`）里拉取，不进生成阶段 ——
   生成阶段调预测会把毫秒级的规则式流水线变成分钟级。取不到就把指标记进 `missingMetrics`（现有语义），
   并把 `realness`（REAL / SIMULATED）写进 `scores[].note`，让「这个分是算出来的还是预测出来的」一眼可见。

### 明确不做

- 不让预测结果绕过硬约束：约束判定只认引擎实算值，预测值再高也不改 `hardPassed`。
- 不在候选排序里偷偷混入预测分：综合分仍按基线权重归一化，预测只是**某个指标的取值来源**。

---

## 9. 修订记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0.0 | 2026-10-14 | 首版：父工程 + synpharm-common + synpharm-gateway；Nacos 接入两个编排文件；单体加可开关的 Nacos 注册；前端 nginx / Vite 代理改指网关 |
| v1.1.0 | 2026-10-14 | 阶段 1：新增 synpharm-auth（33 个 auth 文件从单体迁出）；synpharm-common 按规范 §8.1 重构为单一 ErrorCode；成功码统一为 0 |
| v1.2.0 | 2026-10-14 | 阶段 2A：新增 synpharm-design 模块与 synpharm_design schema；ErrorCode 补 design 段 49 个；设计域 SQL 脚本改 schema 并删 6 条跨 schema 外键 |
| v1.2.1 | 2026-10-14 | 阶段 2B：新增知识库(6) / 约束集(8) / 项目(8) / 轮次(5) 共 27 个端点，全部端到端验证；GatewayAuthProperties 默认值改为安全侧 |
| v1.2.2 | 2026-10-14 | 阶段 2B：新增报告与审批组 5 个端点（32/99），单测 26 + 端到端 66 项全通过；网关 actuator 暴露面收窄为 `health`/`info` |
| v1.2.3 | 2026-10-14 | 阶段 2B：新增湿实验台账 4 个同步端点（36/99），单测 46 + 端到端 48 项全通过（模块累计 162 全绿）；命中判定口径固定为 `HitJudge`（项目优化目标 + 可证伪的有界实测，严格界/非严格界分开处理） |
| v1.2.4 | 2026-10-14 | 阶段 2B：接入 RabbitMQ 并新增任务查询/取消 3 端点（39/99），单测 43 + 端到端 38 项全通过（模块累计 205 全绿，含真实 MQ 往返与重试→死信）；`design_job.uk_idempotent_key` 作为幂等键存储；新增反向回报队列 `design.job.status`；补 design 服务的 `RABBITMQ_*` 环境变量 |
| v1.2.5 | 2026-10-14 | 阶段 2B：报告组接上任务与投递（端到端 77 项全通过），`Idempotency-Key` 缺口闭环，修掉 5 处缺陷（终态任务复用自相矛盾 / `topN` 无下界 / 契约入参被静默丢弃 / `params_json` 缺 `evaluationRunId` / `updateById` 跳过 null）；模块单测累计 212 全绿 |
| v1.3.0 | 2026-10-14 | 阶段 2C：把 auth / design 各存一份的共享设施收敛到 synpharm-common（`CurrentUser` / `InternalRequestFilter` / `SynpharmInternalProperties` / `OwnershipGuard` / `GlobalExceptionHandler` / `PageResult` / `JsonCodec` / `MybatisPlusConfig`），异常映射以更完备的 design 版为基；两个服务启动类改 `scanBasePackages = "com.synpharm"`；common 的 servlet / MyBatis-Plus 依赖声明为 `provided` 以免污染 reactive 网关；单测 212 + 网关 13 全绿，auth / design 均可打包 |
| v1.3.1 | 2026-10-14 | 阶段 3 收尾 + 阶段 5 数据层：asset / platform 模块骨架与数据层落地；`design_tool` 迁入 `synpharm_platform`（12 行种子已搬）；ErrorCode 补 asset(60~69) / platform(70~79) 两段；asset 内网端点 3 个（`/internal/files`）已实测。**方法论收获**：拆分会把「合法的同库外键」变成「违规的跨库外键」，`design_tool` 的 2 条同 schema 外键一搬迁就违规，必须逐表重审 |
| v1.3.2 | 2026-10-14 | **阶段 4：predict 框架接入**。① 新建 `synpharm-predict`（8082、schema `synpharm_predict`、独立账号），接入父 POM `<modules>`、dev 编排（`depends_on` mysql/rabbitmq/nacos/mvn-setup）、`.env`/`.env.example`，放开 `00_create_accounts.sh` 的 predict 行。② 实测：`/actuator/health` UP 且 `db/rabbit/discovery` 全 UP，Nacos 服务数 **7** 且 predict 实例 healthy；predict 账号 `SHOW GRANTS` 仅 `synpharm_predict.*`，读 `synpharm.design_job` 被拒（`ERROR 1142`）；**空数据卷全新部署实测通过**（6 账号 / 6 schema / 跨 schema 外键 0 条 / `file_asset` 在 asset / `design_tool` 在 platform 12 行）。③ **修掉 3 处长期潜伏的缺陷**：(a) `Dockerfile` 的 COPY 列表漏 `synpharm-asset`/`synpharm-platform`/`synpharm-predict` 的 pom —— 父 POM 早已含 asset/platform，**prod 镜像构建自此一直处于坏状态**（`Child module ... does not exist`）；(b) prod `docker-compose.yml` 的 mysql 初始化 env 漏 `PLATFORM_DB_*`/`PREDICT_DB_*`（v1.3.1 只补了 `ASSET_DB_*`，同版落地的 platform 漏了）→ 空卷部署会**静默跳过**该账号；(c) `.env.example` 与部署文档变量表缺 ASSET/PLATFORM/PREDICT 三项。④ 本表去重（asset 曾重复两行、表格被一段 blockquote 截断） |
| v1.4.0 | 2026-10-15 | **阶段 4：预测域代码整体迁入（87 个类）+ 错误码迁移**。① 代码：按 design 的布局落到 `predict/{api,api.dto,application,application.impl,domain.model,infrastructure.persistence,infrastructure.client,pipeline,mq,support}`，包名 `com.synpharm.*` → `com.synpharm.predict.*`；共享设施改用 common（`Result` / `CurrentUser` / `BizException` / `ErrorCode`）。② 错误码：按规范 §4.3 补 `ErrorCode` 的 predict 段 19 个码（原先只有注释没条目），删除 `PredictionErrorCode`（常量名与 ErrorCode 逐字相同，43 处调用点机械改名）；`PredictionException` / `PipelineException` 改为继承 `BizException`，由此获得**真实 HTTP 状态码**（原先一律 HTTP 200 + body 带旧整数码），`errorCode` 字段取值不变。③ 数据：`migrations/2026-10-15-phase4-split-predict-schema.sql` 把 10 张表**非破坏性**复制到 `synpharm_predict`（含 1323 行 DDI 白名单；`synpharm` 侧原表原数据保留，因为单体仍在服务）；新增 `40_legacy_monolith_predict_mirror.sh` 让全新部署也得到这份副本。④ 实测：`/actuator/health` UP（db/rabbit/redis/discovery），Nacos 7 个服务；端到端 14 项断言 13 通过（唯一"失败"是我断言写窄：探针行已返回 `id=4`，只是 DTO 不回显 `result_no`；`synpharm_predict` 4 行 vs `synpharm` 3 行，证明读的是新 schema）；空数据卷全新部署实测通过（6 账号 / 6 schema / 新库 10 表 / 两库 ddi 各 1323 / 外键 8 条且 0 跨 schema）；`mvn clean test` 全 reactor 7 模块 SUCCESS、225 单测全绿。⑤ **修掉 4 个既有缺陷**：(a) `TaskController` 直接读 `SecurityContextHolder`，未认证时**静默返回 null** 并把 null 当 userId 传进查询（改为 `CurrentUser.requireUserId()`，401 fail-closed）；(b) `10_ddi_supported_drug.sql` 没有 `USE`，靠连接默认库，换个默认库就建错 schema；(c) `08_batch_task_alter.sql` 幂等检查硬编码 `TABLE_SCHEMA='synpharm'`，回放到新 schema 会失效（改 `DATABASE()`）；(d) `synpharm-predict` 缺 webflux / redis 依赖（`WebClient` / `StringRedisTemplate` 编译期即失败）。⑥ 另发现**两处环境级隐患**（非代码缺陷，已登记）：共享 `~/.m2` 里的 common jar 曾被并发 Maven 写坏（`ClassFormatError: Invalid method Code length 0`，`javap` 却解析正常，只有 JVM 真加载才暴露）→ `mvn install` 前必须 `clean`；宿主机 VS Code 的 Java 语言服务器会把 ECJ 产物写进共享的 `target/`，与容器构建混用会得到 `Unresolved compilation problem` |
| v1.5.0 | 2026-10-15 | **阶段 4 收尾：predict 切流 + 单体预测域下线；`/api/system/**` 顺带迁到 platform**。① **切流**：网关新增 `predict` 路由（5 个前缀，取自规范 §6）与 `platform-system` 路由，均排在单体兜底之前。验证方式是**决定性的**：只在 `synpharm_predict` 插一条属于该登录用户的探针行（该库 4 行 / `synpharm` 3 行），经网关 `/api/predict/history`、`/api/results` 都能读到 → 证明流量确实落在新服务、且数据源指向新 schema。② **单体下线**：删除 86 个 java（预测域全部）+ 3 个 mapper XML + `SystemController`，单体从 98 个主源文件降到 **11 个**、**零端点**；`GlobalExceptionHandler` 移除两个随代码迁走的异常映射。③ **测试随迁**：单体 `src/test` 原有 9 个测试文件（第一轮漏了测试目录，靠编译暴露）—— 8 个随代码迁入 predict（同规则重写包名；`PredictControllerTest` 从「往 SecurityContext 塞 Authentication」改为「装真实 `InternalRequestFilter` + 带内部令牌头」，并断言真 HTTP 状态码），单体保留 1 个（测的仍是留在单体的 `Result`，移除 2 个已失效用例）。**predict 单测 59 个全绿**，是行为保真的有力证据。④ **`/api/system/**` 迁 platform**：单体最后一个 Controller 依赖已删的 `FastApiClient`，按方案 §4（该端点归 platform 域）连同能力探测一起迁移 —— 新增 `EngineHealthClient`（从 `FastApiClient` 只取出健康探测这一项能力，逐行保持超时/重试/熔断逻辑）、`SystemController`、`AlgorithmHealthResponse`、`SimpleCircuitBreaker`（整体移植）、精简版 `WebClientConfig`。经网关实测 200 且 `status=DOWN`（引擎未部署）；**无 token 也返回 200 与原行为一致**（该路径本就在单体 `permitAll()` 白名单和网关 `public-paths` 里）。⑤ **SQL 侧收尾**：12 个脚本（03~10、26~29）改指向 `synpharm_predict`，删除过渡镜像 `40_`；新增破坏性迁移 `2026-10-15-phase4-cutover-drop-legacy-predict-tables.sql`（先删存量外键 `fk_rpt_session`，再删 `synpharm` 侧 10 张旧表）。⑥ **实测**：经网关端到端 10 项断言 9 通过（唯一"失败"是单体对未映射路径返回 **500** —— 那是它的 catch-all 处理器把 `NoResourceFoundException` 也吞成 500 的**既有行为**，非本次回归）；空数据卷全新部署全绿（6 账号 / 新库 10 表 / `synpharm` 无 predict 表 / 07 测试数据也落到新库 / 8 外键 0 跨 schema / fresh 无 `fk_rpt_session`）；`mvn clean test` 全 reactor 7 模块 SUCCESS、**284 单测**全绿（gateway 13 + design 212 + predict 59）。⑦ 工具链教训：**PowerShell 正则的 `$` 锚点在 CRLF 文件上匹配不到行尾** —— 26~29 四个 CRLF 脚本首轮替换静默失效（计数用无锚点正则说"改 1 处"，替换用带锚点正则实际没改）。⑧ **收敛 `PredictionException`**：迁入后它已是 `BizException` 的**零行为别名**（实测 43 处调用点全部是 `(code, message)` 两参形式，带 cause 的三参形式**一次都没用过**，而 `BizException(ErrorCode, String)` 恰好覆盖），故删除该类、45 处调用点改抛 `BizException`（13 个文件）；`PipelineException` 保留（它有真实的 stage→ErrorCode 映射与工厂方法）。predict 单测仍 59 全绿、端点端到端复跑 8/8 |
| v1.6.0 | 2026-10-15 | **阶段 5 实施：工具注册表与能力矩阵（红线 R1/R2 的运行时）**。① **6 个公开端点**（契约 4.3.11）：`GET/POST /api/design/tools`、`GET/PATCH /api/design/tools/{id}`、`POST /api/design/tools/{id}/probe`、`GET /api/design/capabilities`；外加内网 `GET /internal/tools/capabilities`（design 的命令注册表用它把未部署引擎的命令置灰）。② **`ToolRegistryService`**：`@Scheduled` 10 分钟拉 `GET /v1/evaluate/capabilities`，按 `capability_key` 回写 `available / probe_msg / last_probe_at`；契约的 4 条判定规则逐条实现，并**补齐了规则表未覆盖的 2 种情形**（`enabled=0`、键在矩阵中判不出），都保持「不可用 + 说明原因」的同一形状 —— 不允许出现「没有原因的红点」。写回**必须用 `UpdateWrapper`**：`updateById` 会跳过 null，而探测成功恰恰要把 `probe_msg` 置回 null（此坑本项目已踩过两次）。③ **网关新增独立路由** `platform-design-tools`（`/api/design/tools/**` + `/api/design/capabilities`），**必须排在 design 路由之前**：三条路径的前缀属设计工作台的功能域，但 `design_tool` 表归 platform（规范 §1.1 / 勘误 C-05）。漏了它会被 design 的 `/api/design/**` 静默吃掉并返回 404，而「路由看起来是通的」会让问题极难定位；已用对照实验证明（直连 design=404、直连 platform=200、经网关=200）。④ **修掉 3 处缺陷**：(a) **`/internal/**` 无准入控制**（此前登记的债项：`InternalRequestFilter` 口令不匹配时只忽略身份头、不拒绝）→ 现在 `/internal/**` 上口令不对直接 401，非 `/internal` 路径行为不变（阶段 1 起的既有语义）。服务间专用路径上「忽略身份头后放行」是错的：这类路径多数不读身份，放行等于把内部数据面交给同网络内任何调用方。(b) **`HttpMessageNotReadableException` 未映射** → 请求体里写一个契约之外的枚举值会返回 **500**，而规范早已为它登记 `MALFORMED_REQUEST`(400)。现已映射，并在 `data` 里回传非法值与**允许取值**让 400 可自救；该洞不限于新端点，predict 的 `algoType`、design 的各类枚举字段同样受益。(c) `ToolResponse` 漏了契约里的 `enabled` 字段（写路径正确、响应缺字段，靠端到端断言发现）。⑤ **数据侧发现**：种子里 `pocket_detect.capability_key=pockets`、`molecule_generate=generation` 与引擎键对不上（引擎键是 `p2rank` / `crem`），严格按契约的「先 `engines[key]` 再 `models[key.split('.')[1]]`」会让这两行**永远判为不可用**（假阴）。因 `requires_engine` 列注释即声明「能力探测用」且取值恰好正确，实现加了**兜底 + WARN**：行为正确的同时把数据漂移暴露出来。⑥ **实测**：用一次性容器起假算法引擎（按 04 §2.1 契约构造混合矩阵），12 个工具全部落在预期分支（engines 直命 / `models.*.ready` / `requires_engine` 兜底 / 判不出），且 `available=1` 的工具 `probe_msg` 确实为 null；写接口 20 项断言 + 探测路径 12 项全通过（含「伪造 `available=true` 被忽略」的红线用例、引擎停机 → 502 `TOOL_PROBE_FAILED` 且 `probe_msg` 已回写、引擎恢复 → 重新探测清空 `probe_msg`）；`mvn clean test` 全 reactor **290 单测**全绿（gateway 13 + design 218 + predict 59）。⑦ **权限决定**：契约未规定谁能改工具注册表，但 `endpoint` 会被 design 的命令注册表**直接调用**，若任何登录用户都能改，等于开了一个可以指向任意内网地址的入口 —— 故 `POST/PATCH/probe` 限定管理员（403 FORBIDDEN），只读接口对已认证用户开放。⑧ **platform 单测 19 个**（能力解析 6 / 探测回写 6 / 写路径红线 7：伪造可用性被忽略、`PATCH` 的 UPDATE 语句不含能力三列、非管理员 403）；残余仅有 Sentinel / Tracing（基础设施升级，非服务抽取） |
| v1.7.0 | 2026-10-16 | **design 2B 切片（第 14 组工作台）+ 把 H-21/H-22 从「人工规避」升级为「机制自愈」**。① **第一个服务间同步调用**：`GET /api/design/commands` 要按「工具码 → 名称 / 分类 / 真实可用性」把未部署引擎的命令置灰（红线 R1/R2），而 `design_tool` 归 platform、拆分铁律 R2 又禁止跨库直连。故 platform 新增内网端点 `GET /internal/tools/registry`（公开契约缺命令面板必需的 `name`/`category`，不为内部需求去改冻结的 schema），design 新增 `ToolRegistryClient`（`RestClient`，失败如实转 **503 `DEPENDENCY_UNAVAILABLE`**，**刻意不降级成空命令列表** —— 空面板让人分不清是能力服务挂了还是自己没权限）。② **第 14 组切片 3/11**：`GET/PUT /api/design/workspace`、`GET /api/design/commands`。③ **三个必须写下来的取舍**：`PUT /workspace` 是「不传 = 不变」（与 design 域其它 PATCH 相反，因为前端发的是 `Partial<WorkspaceState>`，按清理会导致只改 `activeTab` 就抹掉已打开的分子页签）；`project_id IS NULL` 的槽位必须先读最近一行再写（`uk_user_project` 在 MySQL 下**不约束 NULL**，`ON DUPLICATE KEY UPDATE` 不会命中，直接插会攒出一堆同义行）；`writes` 与项目状态过滤**无权威数据源**，按最保守规则实现并写进 javadoc。④ **H-22 复发且更严重**（Docker Desktop 重启后 6 个 Java 服务全部重启循环，gateway `RestartCount=24`）：宿主 Java LS 在 `08:17:19` 一次性重写了 `platform/target/classes` 的**全部 31 个 class**（25 个字节码非法），异常却在 Spring `checkLookupMethods` 阶段爆出（`Lookup method resolution failed`），极易误判成 Spring 配置问题；同批还重写了 `synpharm-common/target/classes`，使 `mvn test` 的 gateway 用例全挂（Mockito 报 `Could not modify all classes`，根因同样是 `ClassFormatError`）。**根治**：`.vscode/settings.json` 设 `"java.autobuild.enabled": false` 切断污染源；编排里 6 个 Java 服务启动命令改 `mvn -B clean spring-boot:run`、`mvn-setup` 改 `clean install`（自愈，代价是每次启动多一次全量编译）。⑤ **H-21 复发**（m2 的 common jar 又被写坏）—— 由此确认 `mvn install` **不带 clean** 会按时间戳判定"已是最新"而把 `target/classes` 里的坏 class **直接打进 jar**，这正是 `mvn-setup` 必须 `clean install` 的原因。⑥ 新增 `JarClassCheck`（把 jar 挂 `URLClassLoader` 逐个 `Class.forName` + 反射取方法）：因为 **`javap` 不校验 `Code_length`**，判断字节码好坏只能走 JVM 真加载，不能用 `javap` 验收。⑦ **实测**：端到端 18 项 + 路由/准入/归属 6 项全通过（经网关 `/api/design/tools` = 200、直连 design = **404**、直连 platform = 200；`/internal/tools/registry` 无令牌与错令牌均 **401**；他人项目 **404**）；库内断言「同一用户连续 5 次 PUT 后仍只有 1 行」；全 reactor **324 单测**全绿（gateway 13 + design 232 + platform 20 + predict 59）；11 个容器常驻、7 服务注册 Nacos、重启 0 |
| v1.8.0 | 2026-10-16 | **design 2B 第 5 组：靶点库只读 3 个端点 + 补登记一个缺失的 404 码**。① `GET /api/design/targets`、`GET /api/design/targets/{id}`、`GET /api/design/structures/{id}/pockets`。② **两级嵌套批量加载**：靶点 → 结构 → 口袋，列表与详情**同一形状**都展开全；无论页大小固定 3 条 SQL（`IN` 取本页结构、再取这些结构的口袋）。不做「列表只给壳、详情才给全」的裁剪 —— 同一 schema 在两条路径下语义不同会让前端类型对不上数据（字段永远空数组）。③ **`status` 非法值返回 400 而不是忽略**：它是契约声明的封闭枚举又用作过滤条件，静默吞掉会返回**没筛过的全量列表**（用户以为筛过了），比报错危险；`keyword` 只搜「标识 + 名称」四列，不搜 `description` —— TEXT 列 LIKE 会全表扫（规范 §4.4）。④ **补登记 `STRUCTURE_NOT_FOUND`(40454)**：契约给该端点只登记了 200，但「结构不存在」与「结构存在但没有口袋」必须可区分，都用 200 + 空数组等于告诉前端「这个结构一个口袋都没有」。同步补 openapi 的 404、规范 §4.4 登记表，并修掉那三处**悬空的「（阶段 2B 补登记，见下）」**（此前指向一处并不存在的说明）。⑤ **openapi 补 4 个字段**（`description`/`pathway`/`relatedDiseases`/`knownDrugs`）：契约漏登记，而建表时 `14_target.sql` 就是按前端 `Target` 接口对齐的、这四列都在，`测试数据准备清单.md` §2.A 也把它们列为接口字段（`description` 还是必填）。加字段非破坏性，已登记勘误 E-01。⑥ **自查时发现并修掉一个真问题**：实现原本只声明「仍要求已登录」却**没有真的校验**，实测**直连 `http://design:8083/api/design/targets` 返回 200**（拦住它的一直是网关）。已改为在实现层 `CurrentUser.requireUserId()`，服务自己 fail-closed。⑦ **`filePath` 不外泄**：`target_structure.file_path` 是容器内落盘路径，不进 DTO（有单测用反射锁死）；需要文件一律走 `fileAssetId`。⑧ **实测**：端到端 **52 项全通过**（中文检索与中文数据往返、`status` 大小写不敏感、分页与 `pageSize` 上限 400、两级嵌套与「默认结构排前」、`residues_json` 解成对象数组、0/1→布尔、`filePath` 不出现在响应里、404 与 `VALIDATION_FAILED`、直连 401）；全 reactor **345 单测**全绿（gateway 13 + design 253 + platform 20 + predict 59）。⑨ **两点如实记录**：(a) **未修、已登记 H-27** —— 既有端点 `design /api/design/library` 与 `platform /api/design/capabilities` **直连不带身份头仍返回 200**（同类共享只读数据，服务内也没校验登录；本次新增的 target 三端点与 `commands`/`workspace`/`projects` 均正确 401）；(b) **靶点库当前 0 行** —— `37_design_seed_data.sql` 只 seed 了知识库、指标定义与平台工具，**没有任何靶点数据**，验证用的临时数据（3 靶点/3 结构/3 口袋）已删除、未进任何种子脚本 |
| v1.9.0 | 2026-10-16 | **单体退役 + 遗留表清收（绞杀式迁移收尾）**。① **退役依据（实测）**：单体主源码仅 **11 个文件**且**无任何 Controller/Service/Mapper/Entity**；端点 **0**；`synpharm` 库除 19 张设计域镜像表外**再无其它表**（17 张 0 行，仅两个种子表 22/24 行且 `synpharm_design` 有同一份）；前端**不依赖单体**（`nginx.conf` 已 `proxy_pass http://gateway:8080`）。② **六件事**：(a) DROP `synpharm` 19 张镜像表（先 dump 备份；`FOREIGN_KEY_CHECKS=0` 包裹以过 33 条自引用外键）；(b) 删除过渡期脚本 `39_legacy_monolith_design_mirror.sh`；(c) 搬迁 `synpharm-backend/sql/` → `synpharm-deploy/sql/`，两个 compose 的 `initdb` 挂载改为 `./sql`；(d) 移除 dev/prod 编排的 `backend` 服务 + prod 的两个数据卷 + 顶部构建提示；(e) **删除网关 `monolith` 兜底路由** —— 保留它只会把未匹配路径转到没有端点的服务并得到 500；(f) **新增 `GatewayErrorWebExceptionHandler`** 作为 (e) 的配套：未匹配路由原本落成**无信封的空 body 404**（前端 axios 只会显示"未知错误"），现统一包成信封。③ **实测 14 项全通过**：6 个已实现前缀仍 200；未匹配路径 → `404` + `{"code":40400,"errorCode":"RESOURCE_NOT_FOUND",...}`；**停掉 design 后带令牌请求 → `503` + `{"code":50300,"errorCode":"DEPENDENCY_UNAVAILABLE",...}`**（真实下游不可达）；Nacos 服务数 **7→6**；**独立空卷跑完整 initdb：`synpharm` 0 表**、其余 schema 与种子齐全、跨 schema 外键 0。④ **有意保留并写明理由**：`synpharm-backend/` 源码目录（工作区**非 git 仓库**，删除不可逆；回滚 = 恢复 compose 块 + 网关路由）、`synpharm` 空 schema（compose 的 `MYSQL_DATABASE` 指向它，去掉要牵动下游而空库代价≈0，已在 `00_create_database.sql` 写明"不要再往里建表"）、`BACKEND_PORT` 变量名（它现在指**网关**端口，改名的连锁成本高于收益）。⑤ 全 reactor **349 单测**全绿（gateway 17 = 原 13 + 新增 4 + design 253 + platform 20 + predict 59） |
| v1.10.0 | 2026-10-17 | **技术债收口：H-25 / H-26 / H-27 三项**。① **H-27（安全面）**：`design /api/design/library` 与 `platform /api/design/capabilities`、`/tools`、`/tools/{id}` 在服务内**不校验登录**，只靠网关验签 —— 同网络内绕过网关直连（不带任何身份头）能直接读到知识库与工具注册表（修复前实测 200）。修法：AppService 读入口显式 `CurrentUser.requireUserId()`。**`capabilities()` 与 `registry()` 刻意不加** —— 它们被 `InternalToolController` 以服务间调用复用、那条路径没有用户身份，塞进去会直接打断 design 的命令面板；公开端点的校验因此放在 **controller 层**并注释说明。**实测 13 项全通过**：直连三端点均 401、经网关已登录的 7 个读端点仍 200（未误伤）、`/internal/tools/registry` 仍 200。② **H-25（数据）**：种子 `capability_key` 的 `pockets`/`generation` 改为引擎键 `p2rank`/`crem`（原本靠 v1.6.0 的 `requires_engine` 兜底 + WARN 才行为正确）。种子与线上库同步；`engines` 直命与「命中不回退」两条路径均有单测覆盖。③ **H-26（数据）**：种子管理员注释称口令为 `123456`，实测 BCrypt 校验为 **false**，且该口令本身也过不了强度校验 —— 该账号**无论如何登不进去**。修法：`password` 置 **NULL**，该行仅作 `predict_task`/`predict_result` 的 `user_id=1` 引用锚点；这也顺带消掉 **B-02「弱口令管理员随部署自动创建」**。种子头部写明创建管理员的正确路径（注册 + 提权）。④ 全 reactor **352 单测**全绿（gateway 17 + design **254** + platform **22** + predict 59），新增 3 个守卫用例 |
| v1.11.0 | 2026-10-17 | **design 第 14 组续：排行榜 `GET /api/design/projects/{id}/leaderboard`**。① **双分页**：默认偏移（`total/page/pageSize/hasNext`）；带 `afterScore`+`afterId` 时键集（`nextAfterScore/nextAfterId`，**`total` 为 null** —— 规范 §4.2 要求键集模式无 `total`，每次翻页 COUNT(*) 正是该规则要避免的）。② **偏页 > 1000 → 409 `USE_CURSOR`** 硬拒绝：悄悄退化比明确拒绝更难被发现。③ **`sortBy` 白名单**：`total_score` 或**本轮启用过的 metricCode**；过白名单后仍过 `^[A-Z][A-Z0-9_]{0,39}$` 才拼进 `ORDER BY`（参数化救不了 ORDER BY）。④ **红线 R1**：`weightsSnapshot` 取自 `evaluation_run.metric_weights`（不是指标定义默认值，两者刻意做得不同以证明快照优先）；`redistributed` 由「本页完全没出现的启用指标」推导，原因取失败样本 `error_msg`。⑤ **自查修掉一个真 N+1**：缺失指标原因起初「每个指标查一次」（8 个指标全失败 = 8 次），改为批量；现在结果表查询固定 2 次，与页大小/缺失指标数无关，有单测钉住。⑥ **`sortBy=metricCode` 只排有该指标值的候选**（无值无从排名），故走 `evaluation_result`；实测 `total=4`（5 个候选里 1 个未评分被排除）。⑦ **实测 12 项全通过**（fixture 5 候选/12 结果，含同分与未评分）：排序与 NULL 排最后、权重快照、`redistributed`、409、400、指标排序、键集无 total、他人项目 404、缺 roundId 400；fixture 已删。⑧ 全 reactor **364 单测**全绿（gateway 17 + design **266** + platform 22 + predict 59），新增 12 |
| v1.12.0 | 2026-10-19 | **工作区整理：删除退役单体源码目录**。① 核实单体业务代码早已迁净（src 只剩 15 个框架/边界文件），且 sql/ 已于 v1.9.0 搬到 synpharm-deploy/sql/，故源码目录无保留价值。② **先补最终备份再删**：_precloud 备份 zip 缺 support/CurrentUser.java 与 support/InternalRequestFilter.java（10-14 之后才加的，且已被 synpharm-common 的 ThreadLocal fail-closed 版取代），因此新建 _synpharm-backend-final-backup-20261019.zip（21 文件，逐个校验存在）后才删除。③ **删除**：synpharm-backend/（61 文件 71.5 MB，其中 	arget/*.jar 占 71.3 MB）与 synpharm-backend-main/（纯空构建残留 9 个空目录）。④ **同步修引用**：.idea/{compiler,encodings,misc,workspace}.xml 移除 backend 模块与指向 SynPharmApplication 的默认运行配置（改选 SynpharmAssetApplication）、clone-all.sh/.bat 的 REPOS 改为 synpharm-cloud、两个 compose 与本文档的「源码目录仍保留」说明改为实际状态。⑤ **保留**：6 个 _synpharm-* 备份中的 db dump 与 docs zip；文档中「从单体迁移」的历史叙述按需保留 |
| v1.13.0 | 2026-10-22 | **服务收敛：design + asset + platform → synpharm-core**。① **模块**：三模块合并为一个 `synpharm-core`，`com.synpharm.{design,asset,platform}` 原包名保留（三个原启动类的 `scanBasePackages` 本就是 `com.synpharm`，合并后无需改动），新启动类合并 `@EnableDiscoveryClient` + `@EnableScheduling` + 三包 `@MapperScan`，pom 取依赖并集（唯一差异是 platform 的 webflux）。② **跨服务调用消除**：删 `ToolRegistryClient` / `ToolRegistryEntry` / `InternalToolController`，`WorkspaceAppServiceImpl` 改为注入 `ToolAppService` 进程内调 `registry()`。③ **网关 4 条路由**（auth / core / predict / core-system），**`platform-design-tools` 前置路由消失** —— 那条「必须排在 design 之前否则 `/api/design/tools/**` 被静默吃掉成 404」的隐式依赖不复存在。④ **数据**：三库合成 `synpharm_core` 21 张表 + 单账号；24 个 SQL 脚本的 `USE` 改写，`37_` 里的全限定名 `synpharm_platform.design_tool` 改 `synpharm_core`；`00_create_database.sql` / `00_create_accounts.sh` 同步。注意 `26~29_` 的 `agent_*` / `tool_invocation_log` 属 **predict 域**（`USE synpharm_predict`），未动。⑤ **编排**：dev 13→11 服务、prod 单 `core`，mysql 初始化变量三账号合一。⑥ **验证**：Maven 6 模块 SUCCESS；**单测 364/364**（core 288 = design 266 + platform 22）；**E2E 247 项全绿**（2b 18 + target 52 + h27 14 + job 38 + experiment 48 + report 77）；core health UP、Nacos 服务数 **6→4**、`algorithm-health` 23ms。⑦ **保留**：三个旧库 7 天以便回滚，备份 `_synpharm-cloud-premerge-20261022.zip` + `_synpharm-db-backup-premerge-20261022.sql`
| v1.14.0 | 2026-10-24 | **阶段 B：需求分析（基线八块 + 冻结快照）**。① **数据**：`sql/39_requirement_baseline.sql` 建 `baseline_snapshot` / `project_metric` / `project_risk_item`，改造 `design_project`（`moa_code` / `current_baseline_id` / `project_version` / `quota_*` / `min_candidates`）与 `constraint_rule`（`tolerance` / `boundary_policy` / `applies_to_modality`），字典补 `NANOBODY` / `MOA`×5 / `FILTER_REASON`×15 → `synpharm_core` **24 表**。全脚本走 `information_schema` 守卫，连跑两遍幂等。② **两个设计修正**：`min_candidates` 由 `NOT NULL DEFAULT 10` 改 `NULL` —— 默认值会把「没设」静默变成「设了 10」并让 `NO_MIN_CANDIDATES` 成死代码（违反红线 R2）；`ProjectCreateRequest` / `ProjectPatchRequest` 补 `moaCode` / `minCandidates` / `quotaGpuHours` / `quotaWetLab` —— 原本**没有任何 API 能写这四个字段**，基线块7 永远填不上、项目永远无法冻结。③ **代码**：`BaselineAssembler`（八块装配 + `content_hash`）、`BoundaryJudge`（STRICT / WITHIN_TOLERANCE / ESCALATE_TO_SOFT 三态 + 等号归属）、`BaselineAppService`（preview / freeze 幂等 / current / history）、`ProjectSettingAppService`（指标风险 CRUD + 按形式补齐）、2 个 Controller、7 个 ErrorCode。④ **`LibraryCategory` 补 `MOA` / `FILTER_REASON`** —— 字典表加了分类但枚举没同步，`?category=MOA` 直接 400，前端下拉永远空（E2E 抓到后修）。⑤ **前端**全新 `api/requirement.ts`（无 mock）+ `BaselineBoard` / `BaselineWizard` / `Baseline` 页面 + 路由 + 工作台入口；`request` 补 `patch` 并修正 5 个 `TS2322`。⑥ **验证**：**单测 331/331**（新增 45）、**`e2e-baseline.ps1` 59/59**、`vue-tsc` 0 错、`vite build` 通过（`Baseline` 独立 chunk）。⑦ **注意**：迁移脚本需 **root** 执行（`DROP PROCEDURE` 需 `ALTER ROUTINE`，业务账号没有）|
| v1.15.0 | 2026-10-24 | **阶段 E 设计定稿 + DDL 落地**（`docs/design/detailed/07-决策阶段设计方案.md` + `sql/40_decision.sql`）。① **口径**（A 组已定）：技术性淘汰（`constraint_rule`）与业务性否决（`decision_veto_rule`）**分两层**，`FILTER_REASON` 补 `stage` 元数据区分阶段；证据包**只装引用不装决策产物**（否则回放语义失效）；风险接受**逐候选逐风险点**且 `must_verify` 项不可接受（否则「必须验证」退化成建议）。② **6 张新表**：`evidence_bundle` / `decision_veto_rule` / `decision_record` / `decision_candidate` / `decision_veto_hit` / `risk_acceptance` → `synpharm_core` **30 表**；`design_report` 补 `decision_id` 划清「报告审批」与「候选决策」；`FILTER_REASON` 现有 15 条置 GENERATION + 新增 6 条 `VETO_*` 置 DECISION；6 条全局否决规则种子。③ **B 组建议**（待有真实 `evaluation_freeze` 数据后试算再调）：分层+组内加权、名额=min(湿实验余额, 候选×20%)、`CONFIRMED` 不可改走新版本、回放存三项指纹。④ **落地踩到的三个坑**：唯一键含 NULL 时 MySQL 不去重（种子每次重跑再插一遍，实测 6→12）、`design_report` 已有 `idx_decision` 需换名、库里 15 条 `FILTER_REASON` 全属生成阶段（决策原因必须新增而非改归属）。⑤ **验证**：DDL 连跑三遍幂等（veto 规则稳定 6 条）、**单测 333/333**、**基线 E2E 59/59** 无回归 |
| v1.16.0 | 2026-10-24 | **阶段 C：生成流水线（S0~S9）**。① **数据**：`sql/41_generation.sql` 建 5 张表（`generation_slice` / `generation_candidate_log` / `generation_cache` / `dedup_merge` / `generation_output_snapshot`）+ `generation_run` 14 列（`plan_json` / `plan_hash` / `n_samples` / `batch_size` / `seed` / `degraded` 相关 / `output_snapshot_id` / `valid_cluster_count` / `min_candidates` / `idempotency_key`）+ `candidate_molecule` 9 列 + `uk_run_idempotent`，库 30 → **35 表**，连跑幂等。② **代码**：`GenerationPipeline`（S0 校验 / S4~S9 编排与落库）、`GenerationPlanner`（S2 空间 + S3 计划）、`DedupStage` / `PreFilterStage` / `DiversityQuotaStage` / `FunnelDiagnosis`、`HardRuleParser`、`SequenceHasher`（多链哈希）、`CandidateSource` 两实现（`SEED_LIBRARY` / `SEQUENCE_MUTATION`）、`GenerationAppService` 与 7 个接口。③ **两个真缺陷修掉**：`generation_run.strategies` NOT NULL 无默认值导致首次 insert 500（S0 先占位 `PENDING`）；`submit` 上的 `@Transactional(rollbackFor=Exception)` 会让「失败留痕」失效（去掉事务，改 `markFailed` 显式收尾）。④ **最严重的一个**：`baseline_snapshot.hard_rules_json.paramsJson` 是二次编码的 JSON 文本，按 Map 取会拿到空 Map，导致**所有 `NUMERIC_RANGE` 硬约束在 S6 被当作「字段映射不上」静默跳过**（实测甲醇 MW=32 通过「MW 150~600」判为合格），已抽 `HardRuleParser` 显式兼容 Map / JSON 文本 / 缺失三形态，并用 `HardRuleParserTest` 锁住。⑤ **验证**：**380 单测**（+41）+ `e2e-generation.ps1` **37 项** + `e2e-baseline.ps1` **59 项**全绿 |
| v1.17.0 | 2026-10-24 | **阶段 D：最小评估 + 编辑（E0~E3 + 版本树）**。① **数据**：`sql/42_edit.sql` 建 6 张表（`evaluation_freeze` / `edit_action_mapping` / `edit_gap_report` / `edit_impact_assessment` / `edit_batch` / `edit_delta_report`）+ `candidate_molecule` 5 列（`version_no` / `edit_round` / `branch_status` / `needs_re_eval` / `eval_profile_id`）+ `molecule_edit_log` 7 列 + 7 条映射种子 + 7 条 EDIT 原因码，库 35 → **41 表**；`sql/43_edit_log_link.sql` 补 `generation_candidate_log.temp_ref` 并回填 `candidate_id`。② **代码**：`MinimalEvaluator`（归一化 + 硬约束 + 综合分）、`EvaluationContextBuilder`、`GapDiagnostician`（缺口/保护名单/unfixable 三分）、`ActionMatcher`（三级排序 + 型式过滤 + top-3）、`ImpactAssessor`（回归守卫 BLOCK/WARN/PASS）、`MetricFieldMapper` 与 `Hashes` 公共化；`CandidateEvaluationController`（3 端点）+ `CandidateEditController`（6 端点）。③ **3 个缺陷修掉**：`weight_coverage` 列被 `CREATE TABLE IF NOT EXISTS` 跳过（改列必须另写 ALTER）、`abandon` 返回类型耦合了诊断（新增 `AbandonResponse`）、阶段 C 的日志与候选接不上（`temp_ref` 回填）。④ **验证**：**404 单测**（+24）+ `e2e-edit.ps1` **47 项** + 生成 37 项 + 基线 59 项全绿 |
| v1.18.0 | 2026-10-25 | **前端信息架构重构 + 新建项目入口 + 修字体乱码**。① **成因**：阶段 A~D 三页（基线/生成/评估）都只认 URL 里的 id，前端没有项目列表入口，用户只能手敲地址；`/design` 旧工作台默认走 `VITE_DESIGN_MOCK`，与真数据页面平行存在。② **新增**：`views/Projects.vue`（项目列表 + 每张卡直达三阶段 + 一键体验）+ `/projects` 路由 + 侧栏合并为唯一「🧬 设计项目」入口；`components/design/FlowSteps.vue` 流程条（① 需求分析 → ② 候选生成 → ③ 评估与编辑）。③ **建项目闭环**：表单只填「能立项」的最小信息（名称/分子形式/主靶点/最少候选数/配额，可选内联一条 `NUMERIC_RANGE` 硬约束），提交后自动调 `metrics:init-from-modality` 补指标再跳基线页；生成页缺轮次时给出「开启第 1 轮」按钮（原先只有一句「请先开启一轮」，无处可点）。④ **样式**：`.btn` / `.banner` / `.tag` 提到 `base.scss` 全局（此前只在 Baseline/Profile 各自 scoped 定义，另三页的按钮是浏览器默认灰按钮）；4 个页面补 `<Sidebar />` + 居中容器。⑤ **数据修复**：`sql/44_fix_garbled_names.sql` 修被 `Invoke-RestMethod` 按系统代码页编码写坏的项目名；重跑 `37_design_seed_data.sql` 修复字典表历史双重编码（MODALITY/ASSAY/OBJECTIVE 分类）。⑥ **验证**：`vue-tsc` 0 错、`vite build` 通过、新增 `e2e-new-project.ps1` **10 项全绿**（建项目 → 补指标 → 约束集落地 → 基线预检 → 开轮）|
| v1.18.1 | 2026-10-25 | **排障表补一条环境坑**：Windows 下 Docker bind mount **不传递文件变更通知**（inotify 不可靠），Vite dev server 启动后**永不重新加载任何前端改动** —— `vue-tsc` 0 错、`vite build` 通过、模块请求 200 都发现不了。判定与处理见 §7 末两行 |
| v1.19.0 | 2026-10-25 | **`/design` 从 mock 工作台改成真数据「单分子精修台」**。① **动机**：旧工作台（画布/候选/对比/口袋，约 1200 行）整页走 `designApi` 的 mock 实现，而其真实实现指向的 `/projects/{id}/tree`、`/candidates/ranked`、`/molecules/{id}/metrics`、`/capabilities` 等端点**后端从来没实现过**，所以「后端就绪后翻个开关即可」这个承诺始终不成立。② **重构**：整页重写，只依赖已实测的真实端点 —— 项目 `GET /projects`、冻结基线 `GET /projects/{id}/baseline`（硬约束真值）、批次 `GET /generations?projectId`、候选 `GET /generations/{id}/candidates?stage=S7_QUOTA&outcome=KEPT`（带 smiles）、评估 `POST /candidates/{id}/evaluation:freeze`、缺口 `gaps:diagnose`、影响面 `actions:preview`、版本树 `/tree`。③ **分工**：评估页以**流程**为中心（四页签），精修台以**一个分子**为中心（画布 + 判定 + 代价同屏）。④ **结构真值**：浏览器内 Indigo 实算分子式/分子量/InChIKey，硬约束逐条当场判定（`NUMERIC_RANGE` + `MolWt` 可本地判，其余标「需后端」）。**不能**用生成日志的 `inchikey` 做「改没改」比对 —— 规则式导入路径下它是 null，故改为「载入那一刻的实算结果」当基线。⑤ **Ketcher 子应用首次构建**：`public/ketcher/` 产物此前从未生成，`npm run build:ketcher` 后画布才真能用；缺失时 Vite 的 SPA fallback 会返回 index.html（iframe 装进整个主应用）而不是 404。⑥ **落地页**改 `/projects`（`DEFAULT_HOME_PATH`）。⑦ **验证**：`vue-tsc` 0 错、`/ketcher/index.html` 返回 602 字节真子应用、`/ketcher/assets/index-*.js` 29 MB 200、新增 `e2e-workbench.ps1` **13 项全绿**（项目→基线硬约束→批次→保留候选→冻结 0.3333 覆盖率→诊断 4/6/6→树→影响面 MUTATION/WARN）|
| v1.20.0 | 2026-10-25 | **需求基线不再把 JSON 贴给用户 + 修指标字典乱码**。① **问题**：八块看板展开后是一段 `<pre>{{ 快照原始 JSON }}</pre>`；未冻结时贴的甚至是 `{code,state,required,gaps}` 这种预检报文。理由是「让用户看到生成阶段读到的原文」，但十条指标挤在一段无结构字符串里谁也读不懂 —— 快照的价值是**内容**不是**格式**。② **改法**：`BaselineBoard` 按块渲染人话 —— 靶点/风险是卡片（主靶点标记、角色、UniProt、必须实测），结构是两张表（结构 + 口袋），约束是「判定式 / 类型 / 越界处理 / 权重」表，指标是「指标 / 方向 / 权重 / 判定线」表并给出**权重合计**与**未设阈值项数**，配额是键值对；未冻结时改为解释「这一块要求什么」。展开时占满整行（4~5 列表格挤不进 320px 的格子）。③ **顺手修掉同类两处**：生成页的 S3 计划原本也是原始 JSON（改成 策略/采样/批大小/种子/工具链/降级原因），版本历史的 `HARD_RULES:MISSING` 机器码改成「硬约束 缺失」、`SMALL_MOLECULE` 改成「小分子」。为此抽出 `utils/designLabels.ts` 统一枚举中文，避免每个组件各抄一份漏抄就退回原码。④ **数据修复**（`sql/45` + `sql/46`）：`metric_definition.metric_name/unit` 自 `37_` 种子起就是**双重编码**（`ç±»è¯æ§ QED`），指标名一路带进 `baseline_snapshot.metrics_json`，所以八块里显示乱码。45 用 `latin1→binary→utf8mb4` 精确还原（含两条安全判定，见 §7），46 从业务表重建快照里被破坏过的 `targetType` / `humanReadable` / 风险 `description`（并顺带修好 `constraint_rule` 里一条 `??? 150~600 Da`）。修完 24 条字典名、53 份快照、`constraint_rule` 全部零乱码零 `?`。⑤ **文案**：补齐向导「约束」步骤原写「逐条编辑在‘约束’步骤完成」——它自己就是那一步且没有编辑器，改为如实说明当前只能建项目时内联一条或走接口重建。⑥ **验证**：`vue-tsc` 0 错、`vite build` 通过（Baseline chunk 31.6 kB）、新增 `e2e-baseline-board.ps1` **16 项全绿**（八块形状 + 中文不乱码 + 权重合计 + 配额字段）|
| v1.21.0 | 2026-10-25 | **把两条产品线分开 + 靶点库接真数据**。① **问题**：顶部导航七项平铺，把「设计流程」（需求→生成→评估）与「分子预测」（DTI/PPI/DDI）两条互不往来的产品线混在一起，用户看不出哪些是同一趟流程；而「靶点库」虽然属于设计流程，内容却是 `@/data/mockResults` 里的假数据，于是它看着像个孤立功能。② **导航分组**：`navGroups` 三组 —— 设计流程（设计项目、**靶点库**）/ 分子预测（仪表盘、预测中心、预测结果、任务管理）/ 账号（个人中心），组间竖线分隔 + 10px 组标题；窄屏隐藏组标题以免半字半图标。品牌点击与登录落地页统一到 `/projects`。③ **靶点库改真数据**：新增 `api/target.ts`（`TargetController` 三个端点 + 来源/方法/口袋方法的中文映射），页面重写：列表 → 详情 → **结构与口袋**（含 PDB、链、分辨率、方法、口袋评分/可成药性/体积/选用）。新增「可立项性」判断 —— 无结构=不可立项（块2 MISSING 阻断冻结）、有结构无口袋=缺口袋（对接指标会缺席）、两者都有=可立项；这条判断不是文案，是 `target.structures`/`pockets` 的真数据推出来的（实测 TP53 确实 0 结构）。④ **交叉入口**：基线看板块1/块2 展开处加「去靶点库看结构与口袋 →」（带 `?targetId=`）；靶点库「用此靶点建项目 →」跳 `/projects?targetId=&create=1`，项目列表自动展开建项目表单并预选该靶点。⑤ **记账**：靶点/结构**只读** —— `TargetAppService` 只有 `list`/`get`/`listPockets`，没有新建接口，所以页面不放「新建靶点」按钮，补靶点得走 SQL。⑥ **方案入库**：预测域 → 设计流程的打通方案写进 §8（三步：内部取数端点 / `metric_prediction_source` 映射表 / 只在评估冻结回填），**只写方案未写代码**。⑦ **验证**：`vue-tsc` 0 错、`vite build` 通过、新增 `e2e-target-library.ps1` **14 项全绿**（列表→结构→口袋三层 + 筛选参数 + TP53 无结构）|
| v1.22.0 | 2026-10-25 | **换主题：科研青绿（teal）**。① **原来的问题**：配色是 Tailwind 默认值（深藏青顶栏 `#0f172a` + 品牌蓝 `#3b82f6` + slate 灰），跟药物研发没有关系，看起来就是通用后台；而且全站只写了 `-apple-system, Segoe UI` 没有中文字族，中文落到系统兜底字体，与英文数字混排时重心不齐。② **token 层重写**（`variables.scss`）：主色 teal-600 `#0d9488`（hover `#0f766e`）、顶栏深青 `#0f3d3a`（与白卡对比度 >12:1，WCAG AAA）、底色 `#f5f8f7`（极轻青绿倾向）、文字深青墨 `#0f2b2c`；状态色改 emerald/amber/red/cyan-600 与品牌色区分开；圆角收紧一档（card 16→12、control 10→8）更接近仪器面板；阴影改带青墨色（纯黑阴影在白底偏脏）。③ **字体**：新增 `$font-family-base`（含 PingFang SC / 微软雅黑 / Noto Sans SC）与 `$font-family-mono`，`App.vue`、`base.scss` 的 `body`、以及 `code/kbd/pre/samp` 统一引用 —— 这是本次观感变化最大的一处。④ **清掉硬编码旧配色**：`rgba(59,130,246,…)` 24 处、`#3b82f6`/`#2563eb`、旧灰阶（`#f8fafc`/`#f1f5f9`/`#475569`/`#94a3b8`/`#e2e8f0`）全部替换为 teal 对应值，涉及 Login / Predict / Results / Tasks / Visualization / RoundCompare / AppCard / EmptyState / ResultCard / Targets；`Home.vue` 内联 SVG 分子图与 `MolstarViewer` 的 Z 轴颜色也同步改青。⑤ **顺手修**：`App.vue` 里的 `@import '@/styles/variables.scss'` 与 vite `additionalData` 的注入重复（Sass 会报 `already being loaded`），已删；新写的 `darken()` 全部换成 `mix()`，避免 Dart Sass 的 legacy color function 弃用告警。⑥ **验证**：`vue-tsc` 0 错、`vite build` 通过、dev server 无 Sass 错误、编译后 CSS 实测含 `#0d9488` / `rgba(13,148,136,…)` / `rgba(15,43,44,…)` 且旧蓝 `rgba(59,130,246,…)` 与 `#3b82f6` 已归零 |
| v1.23.0 | 2026-10-25 | **布局：顶部横条导航改为左侧竖向导航**。① **动机**：原来的「Sidebar」其实是 `position: fixed` 的 60px 顶部横条，七个入口平铺；分组标题挤不进 60px 高，窄屏还得把文字全藏掉只剩图标，而下方正文又被这 60px 吃掉一截 —— 标题与首屏内容无法同屏。② **改法**：`Sidebar.vue` 重写为左侧竖排导轨（220px，深青底）—— 品牌在顶、分组导航在中（设计流程 / 分子预测 / 账号三组各有标题）、用户在底；当前栏目用主色底 + 左侧 3px 竖条定位；右缘有收起开关（64px 图标条，状态存 `localStorage['nav_collapsed']`）。③ **关键设计**：正文左偏移**只在 `App.vue` 的 `.app-container` 给一次**（`padding-left: var(--sp-nav-width, 220px)`），宽度由 Sidebar 写入 CSS 变量跟随收起/展开 —— 14 个页面因此一行 `padding-left` 都不用写，换布局只改一处。只有 `meta.requiresAuth === true` 的路由才加这个偏移，落地页与登录页不受影响。④ **14 个页面统一删掉 `padding-top: $header-height`**（顶栏不再占垂直空间），并修掉由此产生的连带问题：`Profile.vue` 四处 `calc(100vh - #{$header-height})` / `top: calc(...)` 的 sticky 偏移改为不含顶栏高度；`ResultDetail.vue` 里「顶栏为 fixed…需留出等高上边距」的过期注释删除。⑤ **验证**：`vue-tsc` 0 错、`vite build` 通过、dev server 无错误；编译后的 App 样式实测为 `.app-container--with-nav { padding-left: var(--sp-nav-width, 220px) }`，`base.scss` 的 `:root` 暴露 `--sp-nav-width-expanded` / `--sp-nav-width-collapsed` / `--sp-nav-width`；`/`、`/login`、`/projects`、`/targets`、`/design` 全部 200，Ketcher 子应用仍正常 |
| v1.24.0 | 2026-10-26 | **阶段 E（决策）E2~E5 落地：装配 → 复核 → 分桶 → 名额**。① **代码**：6 实体（`evidence_bundle` / `decision_veto_rule` / `decision_record` / `decision_candidate` / `decision_veto_hit` / `risk_acceptance`）+ 6 Mapper；3 个支撑类 `EvidenceBundleAssembler`(D1 证据装配，血缘按 `parentMolId` 回溯 ≤20 代并取 `molecule_edit_log.describe_text` 作编辑原因，`contentHashOf` 固定顺序序列化、不含时间/id/结论)、`DecisionVetoEngine`(D2 兜底 + D3 否决)、`DecisionRanker`(D4 分桶 + D5 名额)；`ErrorCode` 补 58/59/65/69/71；`SerialNumbers` 补 EB / DCN；7 端点（`POST /projects/{id}/decisions`、`GET /decisions/{id}`、`GET /projects/{id}/decisions`、否决规则 GET/POST/PUT/DELETE）。② **口径**：决策**只读** `evaluation_freeze` 与由其派生的证据包，不读候选当前状态；证据包只装引用与摘要、不装结论；三不变量（证据先于判断 / 否决不可绕过 / 确认即不可改）；加权分分母只含有数据指标（**缺数据不补零**）并同时给 `coversMetrics`；名额 = min(档位, 湿实验余额) 且 ≥1，FAIL 不占名额不排名次；回放三件套 `weightsSnapshot` + `ruleVersion`(`veto-v1:<hash12>`) + `inputSnapshotHash`。③ **修掉两个真实缺陷**：(a) 冻结快照只记块6 的 10 个加权指标，硬约束引用的 `MOL_WT` 不在其中 → 复核「查不到值」把全部候选判 FAIL、`capacity=0`；改为 `MinimalEvaluator` 把硬约束专用指标也记进 scores（不加权重）+ 装配器对仍无值的规则单列 `NOT_EVALUATED`（不当通过）+ 淘汰原因区分 `VETO_HARD_RECHECK_FAILED`（换分子）与 `VETO_DATA_INCOMPLETE`（补数据）；(b) 全局规则 `DATA_INCOMPLETE` 在 `requireMetrics` 为空时把 `PARTIAL` 也拦，而本系统覆盖率 0.3333 是常态 → 等于永远选不出；改为只拦 `INCOMPLETE`，`PARTIAL` 由 `coversMetrics` 如实展示。④ **验证**：新增单测 27 个（Ranker 8 / VetoEngine 11 / Assembler 8），core 全量 **431 全绿**；`e2e-decision.ps1` **26/26**（命中回放指纹两次一致、`must_verify` 风险全票否决、全局规则 409 不可改、无基线 404 兜底）；回归 `e2e-edit` 47 / `e2e-generation` 37 / `e2e-baseline` 59 全绿 |
| v1.25.0 | 2026-10-26 | **阶段 E10：决策页（前端）+ 补上读取路径的三个缺口**。① **前端**：新增 `/design/projects/:projectId/decision`，四页签 ——「结论总览」（名额来源/实际名额/选中·备选·淘汰·挂起 + 候选表 + 三桶含义）、「否决明细」（按候选列 BLOCK/WARN 命中、规则人话、证据位置，并明确区分「该补数据」与「该换分子」）、「回放指纹」（`ruleVersion` + `inputSnapshotHash` + 权重快照，附**真的再算一次**的重放校验按钮，比对两个 `inputSnapshotHash`）、「否决规则」（6 条全局只读 + 本项目增删改，`humanReadable` 必填）；`FlowSteps` 加第 4 步「决策」；`Projects.vue` 卡片加「决策 →」入口、`一键体验` 改为冻结**全部** KEPT 候选（只冻结第一个的话决策页只剩一行，名额与备选这套机制等于没演示到）；`utils/designLabels.ts` 补分桶/去向/状态/淘汰原因/规则/严重度六张中文表（界面上不再出现 `VETO_MUST_VERIFY_MISSING` 这类机器码）。② **修掉三个读取路径缺口**（都靠新增的 `e2e-decision-ui.ps1` 发现，API 级 E2E 看不见）：(a) `capacity` 只在创建响应里，`GET /decisions/{id}` 返回 null —— 前端刷新后说不出「名额是 1」还是「名额是 3 只选出 1 个」；修法是把名额当快照落库（`sql/47_decision_capacity.sql` 加列），**不在读取时按当前项目配置重算**（项目配置会变，历史决策的解释就被改写，正是「确认即不可改」要防的）；(b) 生成阶段的分子 `molecule_name` 为 NULL，候选表里两行都是「候选 #101/#102」无法分辨 → `DecisionCandidateResponse` 补 `smiles` + `mw`（有则显示，无则不显示）；(c) `GET` 返回 `warnings=[]`，`NO_VIABLE_CANDIDATE` 刷新即消失 → 由 `selected_count` / `rejected_count` 重新推导。③ **验证**：`vue-tsc` 0 错、`vite build` 通过、容器内 dev server 编译该页无报错；core 单测 **431 全绿**；`e2e-decision` **26/26**；新增 `e2e-decision-ui.ps1` **25/25**（真起一套项目→冻结基线→生成→评估冻结，再断言页面的每个取数字段） |
| v1.26.0 | 2026-10-26 | **阶段 E6~E8：人工调整 / 风险接受 / 确认与回放，并把决策页接上「人来拍板」那一步**。① **后端**：`DecisionAppServiceImpl` 把 D0~D5 抽成可复用流水线 `runPipeline(persistBundles)`，创建 / 回放 / 确认走**同一条**路径（各自重算会各自漂）；新增 5 个端点 —— `PATCH /decisions/{id}/candidates/{cid}`（D6 人工调整）、`POST …/accept-risk`（D7）、`POST /decisions/{id}:replay`（D8 回放比对）、`:confirm`、`:defer`、`:cancel`；新增 `DecisionLifecycle`（纯判断，9 个单测）承载三条边界：确认即不可改（非草稿一律 `DECISION_NOT_EDITABLE`）、否决不可绕过（FAIL 桶或 BLOCK 命中的候选不能改成入选，只能改数据重来；往更严方向调永远放行）、回放必须逐条说清差异（不只回布尔值）。② **口径**：确认前**必须**用当前输入+当前规则重算，指纹不一致直接 409 `REPLAY_MISMATCH`（决策可能是几天前算的，拿旧结论下单才是真风险）；确认还要求至少一个候选入选，否则 409 `NO_VIABLE_CANDIDATE`；「必须验证」的风险项不接受（409 `RISK_ACCEPTANCE_FORBIDDEN`）—— 能点「接受」，它就是建议不是要求；人工调整后 `selected/backup/rejected/deferred` 按明细**重算**，不沿用创建时那组数。③ **前端**：决策页顶部按状态出现「确认 / 转暂缓 / 取消」，候选行按行出现「调整」，否决明细里每个风险点单独「接受风险」（`must_verify` 项显示「需补实验数据，不能接受」）；确认弹窗**先展示重算结果**再让人点确认；回放页签从「自己再建一条草稿比对」换成真 `:replay` 端点，直接列逐候选差异（桶/名次/去向）、新增候选、不再参与的候选与基线变更告警。④ **补一个上次漏掉的可视化前提**：`DecisionCandidateResponse` 增 `risks`（证据包里的风险点清单）—— 只给「接受」按钮不给清单等于让人盲签；同时增 `acceptances`。⑤ **验证**：单测 **440 全绿**（新增 `DecisionLifecycleTest` 9 个）；`e2e-decision` **50/50**（新增 24 项：理由必填 400、BLOCK 不可覆盖 409、must_verify 不可接受 409、确认后不可改 409、二次确认 409、无候选可确认 409、暂缓/取消理由必填、`REPLAY_MISMATCH` 真实触发）；`e2e-decision-ui` **36/36**（页面每个取数与写操作走一遍）；回归 `e2e-edit` 47 / `e2e-generation` 37 全绿 |
| v1.27.0 | 2026-10-26 | **找回「分子精修台」的入口 + 让画布在没有项目时也能用**（用户反馈：「从二维分子开始设计的那个功能你搞哪里了」）。① **问题定位**：功能一直在 `/design`（Ketcher 二维画布 + 浏览器内 Indigo 实算 + 按冻结基线硬约束当场判定 + 候选评估/缺口/影响面/版本树，`public/ketcher` 29MB 产物完好），但 v1.23.0 顶部横条改左侧竖排时 `navGroups` 只收了 设计项目 / 靶点库 / 仪表盘 / 预测中心 / 预测结果 / 任务管理，**`/design` 没被带进去** —— 它只残留在「设计项目」的高亮 `match` 里，等于只能手敲 URL 才进得去。这是改版引入的回归，不是功能下线。② **修法**：侧栏「设计流程」组新增「分子精修台 ✏️」，并给 `NavItem` 加 `exact` 标记 —— `/design` 既是自己又是基线/生成/决策页的前缀，不加这个标记会让那三页同时点亮两个入口。③ **顺带修掉第二条断路**：页面原来在「一个项目都没有」时把整页替换成空态，等于「从零画一个分子」这条路根本走不通；现在画布与 Indigo 实算始终可用（它不依赖后端），只在硬约束判定区说明「没选项目时没有基线可对照」。④ **仍然存在的断点**（已登记，未实现）：画完的结构**存不回流程** —— 后端没有「用 SMILES 建候选」的端点（`candidate_molecule` 只由生成流水线 S8 与编辑落库插入），所以画布产物目前只能看/复制 SMILES，不能变成可评估、可决策的候选。⑤ 验证：`vue-tsc` 0 错、`vite build` 通过、dev server 编译 `/src/views/Design.vue` 与 `/src/components/Sidebar.vue` 无报错、`/ketcher/index.html` 与 29MB 化学引擎资源可正常取到 |
| v1.28.0 | 2026-10-26 | **打通「二维画布 → 候选 → 评估冻结 → 决策」**（承接 v1.27.0 找回入口后暴露的真正断点：画完的分子存不回流程）。① **后端**：新增 `POST /api/design/projects/{id}/candidates`（`ManualCandidateController` + `ManualCandidateAppServiceImpl`）—— 输入只有 SMILES（+可选名称），**归一化 SMILES / InChIKey / 分子式 / 理化指标全部由计算层（fastapi-rdkit）实算**，不接受调用方填写：前端能填 InChIKey，就能填出一个「看起来对、其实是另一个分子」的候选，而后续每个判定都建立在「这是不是同一个分子」之上。落 `candidate_molecule` 时 `origin_type=IMPORTED`（人给的分子，区别于 GENERATED），`note` 注明「未经过生成漏斗」。三条边界：引擎没返回的指标留空不补 0；同项目同轮次同 InChIKey **不重复建**（返回已有那条并标 `existed=true`，用户很可能只是想再打开它看看）；项目没开轮次 → 404 `ROUND_NOT_FOUND` 并说明「候选必须属于某一轮」；引擎不可用 → 503 `FASTAPI_UNAVAILABLE`（不是「你的 SMILES 错了」）。② **前端**：精修台顶栏加「存为新候选」，画布脚部加名称输入；保存成功后把新候选并入下拉（单独一个 `manual` 列表，**不伪造生成日志行** —— 塞进 `kept` 会让「这个分子是从漏斗里出来的」变成假话），选中并**立刻评估冻结**，硬约束判定/缺口/影响面当场可用。③ **验证**：单测 440 全绿；新增 `e2e-canvas-candidate.ps1` **11/11**（阿司匹林从另一种 SMILES 写法建出同一 InChIKey、`mw=180.159`、`metricCount=14`、重复建不落库、非法 SMILES 400、无轮次 404、建完直接冻结且两个画布候选一起进入决策）；回归 `e2e-decision` 50 / `e2e-decision-ui` 36 / `e2e-edit` 47 全绿 |
| v1.29.0 | 2026-10-26 | **候选版本写回（画布 → 同一分子的下一版）+ 恢复丢失的 `.env`**。① **版本写回**：新增 `POST /api/design/candidates/{cid}/versions`（`CandidateVersionController` + `CandidateVersionAppServiceImpl`）—— 画布改完结构可直接写成**版本树的下一版**：`parent_mol_id` 指向父版本、`version_no+1`、`edit_round+1`、`origin_type=EDITED`、`needs_re_eval=1`，并**写 `molecule_edit_log`**（`edit_type=RAW_SMILES`、`describe_text` 取用户填的改动说明）。写日志不是仪式：决策阶段的证据包按 `parent_mol_id` 回溯血缘、按 `child_mol_id` 取 `describe_text` 当「这一步是怎么改的」，不写日志的编辑在决策页上只会显示成一串光秃秃的分子 id。**父版本只增不删**（被改掉的那一版是回流沉淀的样本）。四条拒绝：改动说明为空 400 `EDIT_REASON_REQUIRED`、结构无法解析 400 `MOLECULE_EDIT_INVALID`、InChIKey 与父版本相同 400 `MOLECULE_EDIT_NO_CHANGE`（版本树每个节点都得代表一次真实改动）、分支已放弃 409 `BUSINESS_CONFLICT`。② **前端**：精修台画布下方加「改动说明 + 存为新版本」，`canSaveVersion` 用 **InChIKey 是否变化**判定（不是字符串比较 SMILES：同一分子有多种写法）；写回后自动切到新版本并冻结（`needsReEval` 必须先清掉，带着旧分数进决策等于拿上一个分子下单）。③ **环境**：宿主机 `.env` 丢失导致 compose 起不来（`MYSQL_ROOT_PASSWORD` 等为必填）—— 从**仍在的容器**里 `docker inspect` 把口令全部读回并重建 `.env`；同时 `FRONTEND_DEV_PORT` 由 5174 改为 **15174**（重启后 5174 落进 Windows 保留端口段，绑不上）。④ **踩坑**：`molecule_edit_log.params_json` 是 `NOT NULL` 且无默认值，第一版插入直接 500 —— 现在固定写 `{"mode":"RAW_SMILES","source":"CANVAS"}`。⑤ **验证**：单测 440 全绿；新增 `e2e-candidate-version.ps1` **20/20**（邻位→对位换位后 InChIKey `BSYNRYMUTXBXSQ` → `GDBUZIKSJGRBJP`、`mw=180.159`、树 root=父节点 depth=1、`note` 含说明原文、未重新评估时诊断 409 `STALE_OBJECT`、冻结后可诊断、放弃分支拒绝派生）；回归 `e2e-canvas-candidate` 11 / `e2e-decision` 50 / `e2e-decision-ui` 36 / `e2e-edit` 47 / `e2e-generation` 37 / `e2e-baseline` 59 全绿 |
| v1.29.1 | 2026-10-26 | **一键体验延长到决策（含画布路径）**。原流程停在「评估 + 缺口诊断」并跳评估页，决策页永远是空的 —— 而决策才是主线的终点。改后：生成用 2 个种子（阿司匹林会被去重，所以 1 个种子只剩 1 个候选，看不到名额与备选机制）→ 冻结全部 KEPT → **画布演示**（`manualCandidateApi.create` 建候选 + `createVersion` 派生一版，两步都冻结）→ 缺口诊断 → `decisionApi.create` → 跳到决策页。画布那步包了 try/catch：引擎不可用时跳过并在提示里说明，不让整条演示失败。验证：新游客从零跑通（项目 243：基线冻结 → 画布 #181 → 派生 v2 #182 `GDBUZIKSJGRBJP` → 决策名额 1 选中 1 备选 2）；`e2e-canvas-candidate` 11 / `e2e-candidate-version` 20 复跑全绿；`vue-tsc` 0 错、`vite build` 通过 |
| v1.30.0 | 2026-10-26 | **导航重构：去掉 emoji 图标 + 重划分组**（用户反馈「导航页的结构看起来太丑」）。① **病根是 emoji 当图标**：`🧬🧪✏️📊🎯📈📋👤` 的字形/大小/基线由系统字体决定（Windows 走彩色 Segoe UI Emoji、macOS 走 Apple Color Emoji、缺字体环境退化成黑白方块），八个图标放在一列里重量参差、气质也不像科研工具。改为**内联 SVG 描边路径**（24×24、1.6 描边、圆头圆角、无填充，统一走 `currentColor`），8 个图标视觉重量一致且随主题变色。② **结构**：原「账号」组只有「个人中心」一项 —— 单项成组是噪音，已并入底部用户区（点头像进个人中心、退出改为独立图标按钮）；分组从 3 组降到 2 组（药物设计 / 分子预测），组标题右侧加一道**渐隐细线**交代分组边界；「设计流程」改名「药物设计」，条目顺序调整为 设计项目 → 分子精修台 → 靶点库（按查阅频率：先选项目，工具其次，基础数据最后）。③ **视觉**：品牌区改为六边形 logo 标 + 「SynPharm / AI 药物研发平台」双行（原来只有一个 emoji + 文字）；当前项从「整块半透明主色 + 外置竖条」改为淡主色底 + 左缘 3px 竖条 + 图标转 accent 色（收起态竖条同步贴回导轨边缘）；收起开关从白底灰边（落在浅色正文区像贴上去的补丁）改为深色导轨色 + hover 转 accent；补 `:focus-visible` 焦点环；收起态组标题退化为居中细线而非整组隐藏。④ 验证：`vue-tsc` 0 错、`vite build` 通过、dev server 编译该组件与 scoped CSS 均无报错（CSS 8467 字节，关键规则齐全）、`/` `/projects` `/design` `/targets` `/dashboard` `/profile` 全 200 |
| v1.31.0 | 2026-10-26 | **落地页与登录页重做**（承接导航重构，用户：这两页也要优化）。① **删掉落地页的假演示**：原「快速预测演示」用 `Math.random()` 造结合亲和力 / 置信度 / 相互作用，`setTimeout` 1.5 秒后当预测结果展示 —— 这不是演示是编数据，而本项目的底线是「算不出来就说算不出来」（缺指标留空、判不了标待判定）。改为「游客体验」按钮：**真实调用** `authStore.login({loginType:'guest'})` 后进项目列表，那条路径上每个数字都来自引擎与数据库。② **删掉无出处数字**：「10万+ 预测任务 / 95% 准确率 / 500+ 支持靶点」换成三句可在系统里验证的事实（引擎实算不猜值 / 否决不可绕过 / 结论可回放）。③ **修掉截图里的断层**：`10万+` 的 `+` 落单换行（缺 `nowrap`，该元素已随假数据删除）；`position:fixed` 顶栏压住 hero 导致标题贴边（改 `sticky`）；正文定位从「预测平台」改为「设计主线 + 预测域」双线，主 CTA 不再直通 `/predict`。④ **落地页结构**：sticky 顶栏（brand + 3 锚点 + 登录/免费体验）→ hero（双列：标题 + 三句事实 / 右侧「主业务流程」四步卡）→ 设计主线四卡（每步写清留什么痕）→ 分子预测四项（PPI/DTI/DDI/三维视角）→ 三条硬规则 → 技术底座 chips → 底部 CTA → 页脚（品牌统一为 SynPharm，年份取 `new Date().getFullYear()`；原来页脚写「智互药研」+ © 2024）。⑤ **登录页**：左栏 emoji（🧬 🔬 `3️⃣D` 📊）换内联 SVG；文案改为设计主线定位；输入框内 9 处 emoji（📧 🔑 👤 🔒）换新增的 `FieldIcon` 组件（`currentColor` + 绝对居中 + `pointer-events:none`）；左栏渐变从 info 蓝改品牌青绿；补**全页唯一的响应式规则**（原来一个 `@media` 都没有，两个 50% 列在窄屏互相挤压）。⑥ **顺手修一个逻辑不一致**：`navigateAfterLogin` 默认跳 `/dashboard`（预测域），而路由守卫里 `/` 的默认首页是 `/projects` —— 同一个系统两处默认首页不同，登录完落在预测仪表盘而不是设计主线。⑦ 验证：`vue-tsc` 0 错、`vite build` 通过、dev server 重启后加载 `/` `/login` 与两个模块**零编译错误**（此前日志里的 `Unknown word info` 是我用畸形 URL 直接请求 style 模块造成的，非代码问题）、Home CSS 12639 / Login CSS 11276 字节关键规则齐全、字节数 host=container 对齐（Home 27108 / Login 35928 / FieldIcon 1971）、`e2e-canvas-candidate` 11 复跑全绿 |
