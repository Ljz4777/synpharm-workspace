-- =============================================
-- AI 药物分子设计平台 · 工具注册中心
-- 版本：v1.0  依赖：无
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.3.3（权威版本）
-- 落实需求：tools（name/type/endpoint/input_schema/output_schema）
--          + 红线 R1（realness 真实性边界）、R6（去 importlib hack，统一经 Adapter）
--
-- available 是「能力事实」的单一权威：ToolRegistryService 定时（默认 10 min）与按需
-- 调用 FastAPI /v1/evaluate/capabilities 回写；CommandRegistry 与前端命令面板据此置灰。
-- realness 让「真实推理 / 半真实 / 规则近似 / 模拟」四层能力在数据层可分辨。
--
-- ⚠ 能力判定优先级：本表 available 是业务开关的权威，但「引擎是否真的装了」由
--   FastAPI 的能力矩阵决定；两者不一致时以 probe 结果为准（见 04-计算层契约与校准协议.md §3）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS design_tool (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '工具ID',
    tool_code       VARCHAR(64)  NOT NULL COMMENT '唯一工具码，如 predict_dti / molecule_generate',
    name            VARCHAR(200) NOT NULL COMMENT '显示名',
    description     VARCHAR(500)          COMMENT '说明；同时作为第二阶段智能体的工具描述',
    category        VARCHAR(30)  NOT NULL
                    COMMENT 'PREDICTION/GENERATION/EDIT/EVALUATION/STRUCTURE/RETRIEVAL/REPORT',
    type            VARCHAR(20)  NOT NULL DEFAULT 'HTTP' COMMENT 'HTTP/INTERNAL/LLM',
    provider        VARCHAR(20)  NOT NULL DEFAULT 'FASTAPI' COMMENT 'FASTAPI/SPRINGBOOT/EXTERNAL',
    endpoint        VARCHAR(500)          COMMENT 'HTTP 端点，如 /v1/predict/single',
    http_method     VARCHAR(10)  NOT NULL DEFAULT 'POST' COMMENT 'HTTP 方法',
    input_schema    JSON                  COMMENT '入参 JSON Schema（智能体 getParameterSchema 直接复用）',
    output_schema   JSON                  COMMENT '出参 JSON Schema',
    async_execution TINYINT      NOT NULL DEFAULT 1 COMMENT '1=长任务，须经 design_job 编排',
    timeout_ms      INT          NOT NULL DEFAULT 60000 COMMENT '单次调用超时',
    requires_engine VARCHAR(40)           COMMENT '依赖引擎：rdkit/crem/p2rank/vina/admet_ai/kan_modti/flashppi/ddi_llm',
    capability_key  VARCHAR(60)           COMMENT '对应 /v1/evaluate/capabilities 的键，用于能力探测',
    realness        VARCHAR(20)  NOT NULL DEFAULT 'REAL'
                    COMMENT 'REAL/SEMI_REAL/HEURISTIC/SIMULATED —— 真实性边界（红线 R1）',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '业务开关',
    available       TINYINT      NOT NULL DEFAULT 0
                    COMMENT '最近一次探测结果（能力事实，权威来源）；默认 0=未探测，由 probe 置 1',
    probe_msg       VARCHAR(500)          COMMENT '探测失败原因，UI 直接展示（红线 R2：不得"点了才失败"）',
    last_probe_at   DATETIME              COMMENT '最近探测时间',
    agent_exposed   TINYINT      NOT NULL DEFAULT 0 COMMENT '1=暴露给第二阶段智能体',
    agent_tool_name VARCHAR(64)           COMMENT '智能体侧工具名，如 predict_dti',
    version         VARCHAR(20)           COMMENT '工具/模型版本，用于结果可比性声明',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_tool_code (tool_code),
    UNIQUE KEY uk_agent_tool_name (agent_tool_name),
    KEY idx_category_enabled (category, enabled),
    KEY idx_capability (capability_key),
    KEY idx_available (enabled, available)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具注册中心';
