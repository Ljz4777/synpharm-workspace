-- =============================================
-- AI 药物分子设计平台 · 初始化数据
-- 版本：v1.0  依赖：14_ / 20_ / 22_ / 30_ / 32_ / 36_
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.6
--
-- 幂等：全部使用 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行（§8.2 验收项）。
-- 内置数据（builtin=1）的 name / meta_json 可被版本升级覆盖，用户自建项不受影响。
--
-- ⚠ 与设计书的差异（见 docs/design/detailed/00-设计评审与勘误.md R-01）：
--   设计书 ④ 未给出 design_tool.available / probe_msg，若沿用建表默认值会被误读为"可用"。
--   本脚本显式声明 available=0 与 probe_msg，把「引擎未部署 / 端点未实现」的事实
--   在首次探测之前就暴露给 UI（红线 R2：不得"点了才失败"）。
--   并刻意把 available / probe_msg / last_probe_at 排除在 ON DUPLICATE KEY UPDATE 之外，
--   避免重跑本脚本覆盖线上探测结果。
--
-- ✅ 2026-10-17 修正（问题总账 H-25）：`capability_key` 曾有两行与引擎键对不上 ——
--   `pocket_detect` 写 `pockets`（引擎键是 `p2rank`）、`molecule_generate` 写 `generation`
--   （引擎键是 `crem`）。按契约的解析规则（先 `engines[key]`、再 `models[key.split('.')[1]]`）
--   这两行会**永远判为不可用**（假阴：引擎部署了也显示不可用）。
--   已改为与 `requires_engine` 一致的引擎键，CapabilityResolver 里的 `requires_engine`
--   兜底与 WARN 仍保留（防未来再写错时行为正确且漂移可见）。
-- =============================================
USE synpharm_core;

-- ---------- ① 分子形式（MODALITY） ----------
INSERT INTO design_library_item (category, code, name, name_en, level_no, path, meta_json, sort_no, builtin)
VALUES
 ('MODALITY','SMALL_MOLECULE','小分子','Small molecule',1,'/MODALITY/SMALL_MOLECULE',
  '{"defaultDimensions":["AFFINITY","ADMET","SYNTHESIZABILITY","SELECTIVITY"],
    "defaultEditStrategies":["SIDE_CHAIN","SCAFFOLD_HOP"],"seqRequired":false}',10,1),
 ('MODALITY','ANTIBODY','抗体','Antibody',1,'/MODALITY/ANTIBODY',
  '{"defaultDimensions":["AFFINITY","STABILITY","EXPRESSION","DEVELOPABILITY","IMMUNOGENICITY"],
    "defaultEditStrategies":["MUTATION","HUMANIZATION","GLYCO_OPT"],"seqRequired":true}',20,1),
 ('MODALITY','PEPTIDE','多肽','Peptide',1,'/MODALITY/PEPTIDE',
  '{"defaultDimensions":["AFFINITY","STABILITY","EXPRESSION","ADMET","SELECTIVITY"],
    "defaultEditStrategies":["MUTATION","SIDE_CHAIN"],"seqRequired":true}',30,1),
 ('MODALITY','BISPECIFIC','双特异性抗体','Bispecific',1,'/MODALITY/BISPECIFIC',
  '{"defaultDimensions":["AFFINITY","STABILITY","EXPRESSION","DEVELOPABILITY","IMMUNOGENICITY"],
    "defaultEditStrategies":["LINKER","MUTATION","HUMANIZATION"],"seqRequired":true}',40,1),
 ('MODALITY','ADC','抗体偶联药物','Antibody-drug conjugate',1,'/MODALITY/ADC',
  '{"defaultDimensions":["AFFINITY","STABILITY","DEVELOPABILITY"],
    "defaultEditStrategies":["LINKER","SIDE_CHAIN"],"seqRequired":true}',50,1),
 ('MODALITY','FUSION_PROTEIN','融合蛋白','Fusion protein',1,'/MODALITY/FUSION_PROTEIN',
  '{"defaultDimensions":["AFFINITY","STABILITY","EXPRESSION","IMMUNOGENICITY"],
    "defaultEditStrategies":["LINKER","INTERFACE_REDESIGN"],"seqRequired":true}',60,1)
ON DUPLICATE KEY UPDATE name = VALUES(name), name_en = VALUES(name_en), meta_json = VALUES(meta_json);

-- ---------- ② 设计目标维度（OBJECTIVE，8 个） ----------
INSERT INTO design_library_item (category, code, name, level_no, path, meta_json, sort_no, builtin)
VALUES
 ('OBJECTIVE','AFFINITY','结合亲和力',1,'/OBJECTIVE/AFFINITY',
  '{"dimension":"AFFINITY","operator":"GTE","threshold":7,"unit":"pIC50"}',10,1),
 ('OBJECTIVE','STABILITY','稳定性',1,'/OBJECTIVE/STABILITY',
  '{"dimension":"STABILITY","operator":"GTE","threshold":60,"unit":"°C"}',20,1),
 ('OBJECTIVE','EXPRESSION','表达量',1,'/OBJECTIVE/EXPRESSION',
  '{"dimension":"EXPRESSION","operator":"GTE","threshold":50,"unit":"mg/L"}',30,1),
 ('OBJECTIVE','ADMET','吸收分布代谢排泄毒性',1,'/OBJECTIVE/ADMET',
  '{"dimension":"ADMET","operator":"GTE","threshold":0.7,"unit":"score"}',40,1),
 ('OBJECTIVE','SYNTHESIZABILITY','可合成性',1,'/OBJECTIVE/SYNTHESIZABILITY',
  '{"dimension":"SYNTHESIZABILITY","operator":"LTE","threshold":4,"unit":"SA"}',50,1),
 ('OBJECTIVE','DEVELOPABILITY','可开发性',1,'/OBJECTIVE/DEVELOPABILITY',
  '{"dimension":"DEVELOPABILITY","operator":"GTE","threshold":0.7,"unit":"score"}',60,1),
 ('OBJECTIVE','SELECTIVITY','选择性/脱靶风险',1,'/OBJECTIVE/SELECTIVITY',
  '{"dimension":"SELECTIVITY","operator":"GTE","threshold":3,"unit":"fold"}',70,1),
 ('OBJECTIVE','IMMUNOGENICITY','免疫原性',1,'/OBJECTIVE/IMMUNOGENICITY',
  '{"dimension":"IMMUNOGENICITY","operator":"LTE","threshold":0.3,"unit":"score"}',80,1)
ON DUPLICATE KEY UPDATE name = VALUES(name), meta_json = VALUES(meta_json);

-- ---------- ③ 检测类型（ASSAY） ----------
INSERT INTO design_library_item (category, code, name, level_no, path, meta_json, sort_no, builtin)
VALUES
 ('ASSAY','IC50','半数抑制浓度',1,'/ASSAY/IC50','{"unit":"nM","higherBetter":false}',10,1),
 ('ASSAY','EC50','半数效应浓度',1,'/ASSAY/EC50','{"unit":"nM","higherBetter":false}',20,1),
 ('ASSAY','KD_SPR','SPR 解离常数',1,'/ASSAY/KD_SPR','{"unit":"nM","higherBetter":false}',30,1),
 ('ASSAY','KD_BLI','BLI 解离常数',1,'/ASSAY/KD_BLI','{"unit":"nM","higherBetter":false}',40,1),
 ('ASSAY','THERMAL_SHIFT','热稳定性 Tm',1,'/ASSAY/THERMAL_SHIFT','{"unit":"°C","higherBetter":true}',50,1),
 ('ASSAY','EXPRESSION_YIELD','表达产量',1,'/ASSAY/EXPRESSION_YIELD','{"unit":"mg/L","higherBetter":true}',60,1),
 ('ASSAY','SERUM_STABILITY','血清稳定性',1,'/ASSAY/SERUM_STABILITY','{"unit":"%","higherBetter":true}',70,1),
 ('ASSAY','IMMUNOGENICITY_TCELL','T 细胞免疫原性',1,'/ASSAY/IMMUNOGENICITY_TCELL','{"unit":"score","higherBetter":false}',80,1)
ON DUPLICATE KEY UPDATE name = VALUES(name), meta_json = VALUES(meta_json);

-- ---------- ④ 工具注册（含 realness 真实性边界与「当前真实可用性」） ----------
-- 2026-10-22 服务收敛后 design_tool 与设计域同库（synpharm_core），本脚本开头已是
-- USE synpharm_core，理论上可直接写表名；保留全限定名是为了让「这张表原属 platform 域」
-- 这件事在 SQL 里仍然显式可见。
INSERT INTO synpharm_core.design_tool
 (tool_code, name, description, category, type, provider, endpoint, async_execution, timeout_ms,
  requires_engine, capability_key, realness, available, probe_msg, agent_exposed, agent_tool_name, version)
VALUES
 ('predict_dti','药物-靶点亲和力预测','预测小分子与蛋白靶点的结合置信度/亲和力。',
  'PREDICTION','HTTP','FASTAPI','/v1/predict/single',1,1800000,'kan_modti','models.dti','REAL',0,
  '首次能力探测未执行；端点契约待冻结（引擎当前为 POST /v1/predict/single，algo_type=DTI）',1,'predict_dti','1.0.0'),
 ('predict_ppi','蛋白-蛋白相互作用预测','预测蛋白对之间的相互作用概率。',
  'PREDICTION','HTTP','FASTAPI','/v1/predict/single',1,1800000,'flashppi','models.ppi','REAL',0,
  'FlashPPI 权重缺失（models/FlashPPI/weights/model.safetensors）→ /health/ 报 weights_missing',1,'predict_ppi','1.0.0'),
 ('predict_ddi','药物-药物相互作用预测','预测两药联合的相互作用风险（受 1323 条白名单边界限制）。',
  'PREDICTION','HTTP','FASTAPI','/v1/predict/single',1,1800000,'ddi_llm','models.ddi','REAL',0,
  '首次能力探测未执行；端点契约待冻结（引擎当前为 POST /v1/predict/single，algo_type=DDI）',1,'predict_ddi','1.0.0'),
 ('mol_properties','分子理化性质与成药性','RDKit 描述符、QED、Lipinski/Veber、PAINS/BRENK/NIH、SA Score。',
  'EVALUATION','HTTP','FASTAPI','/v1/evaluate/properties',1,300000,'rdkit','rdkit','REAL',0,
  '端点 /v1/evaluate/properties 尚未实现（RDKit 已随算法镜像安装）',1,'mol_properties',NULL),
 ('mol_diversity','候选集多样性','与同批候选的 Tanimoto 相似度统计。',
  'EVALUATION','HTTP','FASTAPI','/v1/evaluate/properties',1,300000,'rdkit','rdkit','REAL',0,
  '端点尚未实现（RDKit 已随算法镜像安装）',1,'mol_diversity',NULL),
 ('molecule_generate','分子生成','按策略与约束批量产出候选分子。',
  'GENERATION','HTTP','FASTAPI','/v1/generate',1,3600000,'crem','crem','REAL',0,
  'CReM 生成引擎未部署；端点 /v1/generate 未实现',1,'molecule_generate',NULL),
 ('molecule_edit','分子编辑','侧链/骨架/突变/Linker/界面/人源化/糖基化编辑。',
  'EDIT','HTTP','FASTAPI','/v1/molecules/edit',0,30000,'rdkit','rdkit','REAL',0,
  '端点 /v1/molecules/edit 尚未实现（RDKit 已随算法镜像安装）',1,'molecule_edit',NULL),
 ('pocket_detect','结合口袋检测','结构上的可设计空腔检测（P2Rank/FPocket）。',
  'STRUCTURE','HTTP','FASTAPI','/v1/pockets/detect',1,300000,'p2rank','p2rank','REAL',0,
  'P2Rank 未部署；端点 /v1/pockets/detect 未实现',1,'pocket_detect',NULL),
 ('mol_docking','分子对接','Vina/smina 对接打分与 RMSD。',
  'EVALUATION','HTTP','FASTAPI','/v1/evaluate/docking',1,600000,'vina','vina','REAL',0,
  'Vina/smina 未部署 → 该指标权重自动重分配',0,NULL,NULL),
 ('admet_predict','ADMET 预测','吸收/分布/代谢/排泄/毒性预测。',
  'EVALUATION','HTTP','FASTAPI','/v1/evaluate/admet',1,600000,'admet_ai','admet','SEMI_REAL',0,
  'admet_ai 未部署；端点未实现',1,'admet_predict',NULL),
 ('report_generate','报告生成','汇总候选、指标、血缘、风险与能力边界，产出 Top-N 报告。',
  'REPORT','INTERNAL','SPRINGBOOT',NULL,1,300000,NULL,NULL,'REAL',0,
  '随 /api/design/reports 一并实现（内部调用，无外部端点）',0,NULL,NULL),
 ('literature_search','文献检索','PubMed 文献检索（第二阶段）。',
  'RETRIEVAL','HTTP','FASTAPI','/v1/retrieval/pubmed',1,120000,NULL,NULL,'REAL',0,
  '第二阶段功能，未实现',1,'literature_search',NULL)
ON DUPLICATE KEY UPDATE
  name           = VALUES(name),
  description    = VALUES(description),
  endpoint       = VALUES(endpoint),
  requires_engine= VALUES(requires_engine),
  capability_key = VALUES(capability_key),
  realness       = VALUES(realness);
-- 注意：available / probe_msg / last_probe_at 故意不在 UPDATE 列表内 —— 保留线上探测结果。

-- ---------- ⑤ 生物大分子补充指标 ----------
INSERT INTO metric_definition
 (metric_code, dimension, metric_name, category, value_type, unit, better, min_value, max_value,
  weight, sort_no, applies_to, remark)
VALUES
 ('TM_STABILITY','STABILITY','热变性温度 Tm','PROPERTY','NUMERIC','°C','HIGHER',30,100,0.1500,200,
  '["ANTIBODY","PEPTIDE","FUSION_PROTEIN"]','计算/实验折半时需标注口径'),
 ('EXPRESSION_YIELD_PRED','EXPRESSION','表达产量预测','PROPERTY','NUMERIC','mg/L','HIGHER',0,500,0.1500,210,
  '["ANTIBODY","PEPTIDE","FUSION_PROTEIN"]',NULL),
 ('AGGREGATION_RISK','DEVELOPABILITY','聚集风险','DEVELOPABILITY','NUMERIC','score','LOWER',0,1,0.1200,220,
  '["ANTIBODY","BISPECIFIC","FUSION_PROTEIN"]',NULL),
 ('VISCOSITY_RISK','DEVELOPABILITY','高浓度黏度风险','DEVELOPABILITY','NUMERIC','score','LOWER',0,1,0.1000,230,
  '["ANTIBODY","BISPECIFIC"]',NULL),
 ('IMMUNOGENICITY_SCORE','IMMUNOGENICITY','免疫原性风险','IMMUNOGENICITY','NUMERIC','score','LOWER',0,1,0.1500,240,
  '["ANTIBODY","PEPTIDE","FUSION_PROTEIN"]','人源化效果的直接度量'),
 ('PEPTIDE_HALF_LIFE','STABILITY','肽半衰期','PROPERTY','NUMERIC','h','HIGHER',0,240,0.1000,250,
  '["PEPTIDE"]',NULL),
 ('SERUM_STABILITY_PCT','STABILITY','血清稳定性','PROPERTY','NUMERIC','%','HIGHER',0,100,0.1000,260,
  '["PEPTIDE","ANTIBODY"]',NULL)
ON DUPLICATE KEY UPDATE
  metric_name = VALUES(metric_name),
  dimension   = VALUES(dimension),
  applies_to  = VALUES(applies_to);
