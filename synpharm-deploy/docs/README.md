# SynPharm 文档中心

> **全部文档的唯一入口** ｜ 最后更新：2026-10-14
> 本文件只做两件事：① 告诉你**该看哪份**；② 告诉你**以哪份为准**。

---

## 1. 我要做什么？（按任务导航）

| 我要… | 看这份 | 备注 |
|---|---|---|
| 了解项目是什么 | [`SynPharm项目书.md`](./SynPharm项目书.md) | 唯一的产品/背景说明 |
| 新人入门（系统怎么跑） | [`architecture/架构设计文档.md`](./architecture/架构设计文档.md) | 含架构图 + 5 张流程时序图 |
| **看业务流程全貌** | [`uml/业务流程.md`](./uml/业务流程.md) | **7 张图内嵌 Mermaid，打开即可看** |
| **看设计域 UML** | [`uml/设计域.md`](./uml/设计域.md) | **13 张图内嵌 Mermaid**（含 33 张表数据模型、5 个状态机） |
| 做微服务拆分 | [`architecture/SpringCloud微服务重构方案.md`](./architecture/SpringCloud微服务重构方案.md) | 最新（2026-10-14） |
| **跑微服务骨架（Nacos / 网关 / auth / design）** | [`../../synpharm-cloud/README.md`](../../synpharm-cloud/README.md) | **阶段 0、1、2A 已实施**：模块结构、启动步骤、排障 |
| 写代码前确认规范 | [`conventions/README.md`](./conventions/README.md) | 接口/错误码/配置/数据/事务/安全/工程 |
| 开发设计域（干实验工作台） | [`design/detailed/README.md`](./design/detailed/README.md) | 可直接实施的层级 |
| 开发预测模块 | [`modules/predict/`](./modules/predict/) | 见 §2 权威裁决 |
| 开发账号/认证 | [`modules/auth/用户认证模块技术设计文档.md`](./modules/auth/用户认证模块技术设计文档.md) | |
| 查还剩什么问题没修 | [`development/未修复功能清单.md`](./development/未修复功能清单.md) | 一页摘要（**详版在总账**） |
| 查某个问题的完整证据与方案 | [`development/SynPharm问题总账与整改方案（整合版）.md`](./development/SynPharm问题总账与整改方案（整合版）.md) | 自称"唯一权威问题总账" |
| 提代码 / 提交信息 / 跑测试 | [`development/开发规范与工程指南.md`](./development/开发规范与工程指南.md) | Git 分支、提交、测试命令 |
| 查接口契约 | [`design/detailed/openapi.yaml`](./design/detailed/openapi.yaml)（设计域）<br>[`api/接口文档.md`](./api/接口文档.md)（**仅已实现**的 40+ 接口） | |
| 部署 / 上线 | [`deploy/`](./deploy/) | 部署与容器化、上线流程 |
| 做二阶段（多智能体） | [`development/第二阶段开发文档-多智能体协同与智能体闭环.md`](./development/第二阶段开发文档-多智能体协同与智能体闭环.md) | |

---

## 2. 以哪份为准？（权威裁决表）★

> 同一主题往往有多份文档（上游基线 / 细化版 / 已被取代）。**这张表是唯一裁决依据。**

| 主题 | ✅ 权威（以此为准） | ⚠️ 已被取代并**已删除**的文档 |
|---|---|---|
| 项目背景 | `SynPharm项目书.md` | — |
| 系统架构总览 | `architecture/架构设计文档.md` | `SynPharm项目整体架构培训文档.md`（派生教学版） |
| **微服务拆分** | `architecture/SpringCloud微服务重构方案.md`（方案 + 阶段 0/1/2A 记录）<br>`../../synpharm-cloud/README.md`（工程与启动） | — |
| **工程规范** | `conventions/`（5 份） | — |
| **业务流程 UML** | `uml/业务流程.md`（Mermaid 内嵌，7 张） | PlantUML 旧版 7 张 + 原 README（**已删除**） |
| **设计域（干实验）** | `design/detailed/`（7 份 + `openapi.yaml`）<br>`uml/设计域.md`（13 张图） | `AI药物分子设计平台-详细设计书.md`（上游基线）<br>`设计域模块-00~07`（上游基线，共 8 份） |
| **预测模块** | `modules/predict/`（6 份） | `预测核心模块技术设计文档-重复.md`（前 4 章与 `modules/predict` 逐字重复） |
| 账号与认证 | `modules/auth/用户认证模块技术设计文档.md` | `用户认证模块开发指南.md`（开发操作版，从属同一主题） |
| **问题与整改** | `development/SynPharm问题总账与整改方案（整合版）.md` | `SynPharm后端修复说明文档.md`（一次性，已被总账收录） |
| **二阶段 / 智能体** | `development/第二阶段开发文档-多智能体协同与智能体闭环.md` | `二阶段-已被取代.md`（含阶段一内容） |
| 接口契约 | `design/detailed/openapi.yaml`（设计域）<br>`api/接口文档.md`（**仅已实现**接口） | — |
| 部署 | `deploy/`（2 份） | — |
| 历史操作记录 | — | `从GitHub覆盖现有代码操作记录.md` |

> `development/未修复功能清单.md` **不是重复**，它是总账的**摘要视图**（文首已声明），需与总账同步维护。

---

## 3. 新人入门（按顺序读 3 份）

```
① SynPharm项目书.md                        15 分钟  项目是什么、给谁用
② architecture/架构设计文档.md              30 分钟  系统怎么跑（含架构图 + 流程时序）
③ uml/业务流程.md                          20 分钟  业务流程全貌（7 张图，标注已实现/未实现）
```

随后按角色分叉：

| 角色 | 继续读 |
|---|---|
| 后端（预测） | [`modules/predict/`](./modules/predict/) → [`api/接口文档.md`](./api/接口文档.md) |
| 后端（设计域） | [`design/detailed/README.md`](./design/detailed/README.md) → [`conventions/`](./conventions/) |
| 后端（账号） | [`modules/auth/用户认证模块技术设计文档.md`](./modules/auth/用户认证模块技术设计文档.md) |
| 前端 | [`design/detailed/02-接口层详细设计.md`](./design/detailed/02-接口层详细设计.md) + [`openapi.yaml`](./design/detailed/openapi.yaml) |
| 运维 | [`deploy/`](./deploy/) |

---

## 4. 全量清单

### 4.1 活跃文档

| 目录 | 文件数 | 内容 |
|---|---|---|
| `./` 根 | 2 | 项目书、本索引 |
| `api/` | 1 | 接口文档（已实现部分） |
| `architecture/` | 2 | 架构设计文档、微服务重构方案 |
| `conventions/` | 5 | 规范总则、接口与错误码、数据与配置、事务消息与安全、工程 |
| `deploy/` | 2 | 部署与容器化指南、上线流程详细版 |
| `design/detailed/` | 8 | 设计域细化设计 7 份 + `openapi.yaml` |
| `development/` | 5 | 问题总账、未修复清单、开发规范、二阶段、测试数据清单 |
| `modules/auth/` | 2 | 认证模块技术设计 + 开发指南 |
| `modules/predict/` | 6 | 后端 / FastAPI / 批量 / 数据流 / 算法与集成 / 输入解析器 |
| `uml/` | 3 | 业务流程（7 张 Mermaid 内嵌）、设计域（13 张 Mermaid 内嵌）、UML 索引 |

### 4.2 已删除文档（**备份可恢复**）

2026-10-14 精简时删除了 **36 份**已被取代的文档（分两批）。

> ⚠️ **本工作区不是 git 仓库**，删除不可从版本控制恢复。因此**删除前均已备份**：

```
备份 1（原归档区 23 份）：
  d:\synpharm-workspace\_synpharm-docs-archive-backup-20261014.zip     190 KB ｜ 23 条目
备份 2（PlantUML 图 13 张）：
  d:\synpharm-workspace\_synpharm-docs-plantuml-backup-20261014.zip     29 KB ｜ 13 条目

恢复命令：
  Expand-Archive -Path <上述 zip> -DestinationPath <目标目录>
```

**备份 1 · 归档区（23 份）**

| 类别 | 份数 | 说明 |
|---|---|---|
| 上游基线 | 9 | 设计书 + 设计域模块 00~07（已被 `design/detailed/` 取代） |
| PlantUML 旧版 | 8 | 业务流程图的 PlantUML 版（已被 Mermaid 版取代） |
| 派生版本 | 1 | 架构培训文档 |
| 重复内容 | 1 | 预测核心模块（与 `modules/predict/` 逐字重复） |
| 已被取代 | 1 | `二阶段.md`（含阶段一内容） |
| 一次性记录 | 2 | 后端修复说明、GitHub 覆盖记录 |
| 索引 | 1 | 原 `_archive/README.md` |

**备份 2 · 设计域 PlantUML（13 张）**

已被 [`uml/设计域.md`](./uml/设计域.md) 的 Mermaid 版取代：

```
01-usecase · 02-component · 03-class · 04-sequence-main · 05-sequence-feedback
06-state · 07-activity · 08-数据模型 · 09-实现分层 · 10-编辑快车道时序
11-能力真实性机制 · 12-校准工件状态机 · 13-校准回灌时序
```

> 活跃文档中原本指向这些文件的 **17 处引用已改为指向 `uml/设计域.md` 或降级为纯文本**，链接检查为 0 断链。
> 至此项目内**已无任何 `.puml` 文件**，Mermaid 是唯一的图载体。

---

## 5. 维护约定

| 规则 | 说明 |
|---|---|
| 新增文档 | **必须**在本文件 §4 登记，并在 §1/§2 建立入口或裁决 |
| 主题冲突 | 先查 §2；已有权威则**追加到权威文档**，不新建 |
| 文档头 | 含：版本 / 日期 / 状态 / 适用范围 / 上游依据 |
| 被取代的文档 | **先备份再删除**（本工作区无 git，删除不可恢复）。删除后在本文件 §4.2 登记，并把指向它的引用降级为纯文本 |
| 图 | **一律内嵌 Mermaid**（GitHub / VS Code 直接渲染，无需插件）。**项目已不使用 PlantUML** |
| 校验收尾 | ① Markdown 相对链接**全部可达**且**锚点有效**（当前 305 处，0 断链）；② 内嵌 Mermaid 语法可解析（用 `mermaid` 官方解析器逐块 `parse`，当前 61/61 通过） |

---

## 6. 修订记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0.0 | 2026-10-14 | 首版：建立唯一入口（按任务导航 + 权威裁决表 + 全量清单） |
| v2.0.0 | 2026-10-14 | **文档精简**：归档 14 份、合并 12 份、UML 目录 3→1、修复 21 处断链；业务流程改为 Mermaid 内嵌 |
| v3.0.0 | 2026-10-14 | 删除归档区 23 份（已备份）；**设计域 13 张 UML 也改为 Mermaid 内嵌**并删除全部 `.puml`；项目改为**纯 Mermaid** 图载体 |
| v3.0.1 | 2026-10-14 | 收尾：修复 8 处此前漏检的断链（合并后遗留的 `data/`、`algorithm/` 子目录引用改为**段内锚点**）并新增**锚点有效性**校验；上游 A-7 / R-16「两张 puml 无法渲染」更正为**渲染命令缺 `-charset UTF-8`**（勘误已失效）；清除残留的 PlantUML 提法 |
| v3.0.2 | 2026-10-14 | 阶段 2A 落地后同步入口状态：微服务骨架提及 auth / design 两个已落地服务；方案文档补 §8.3.1（跨 schema 外键实测 6 条）与 §11.3（阶段 2A 实现说明） |
