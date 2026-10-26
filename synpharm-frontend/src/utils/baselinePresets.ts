/**
 * 需求基线的「推荐值」。
 *
 * 为什么需要这个文件：八块基线原本是纯校验式的 —— 用户被要求「至少一条硬约束」
 * 「有风险关注点」，却没有任何可选的现成值，只能自己敲 `PTM_HOTSPOT` 这种机器码。
 * 结果就是向导能点开、但根本填不出来。
 *
 * 这里给的是**可被后端校验器接受的**模板：字段名走 `MetricFieldMapper` 的映射表
 * （`MolWt` / `LogP` / `TPSA` …），params 结构与 `18_constraint_rule.sql` 尾注一致，
 * 因此选中后直接提交就能通过 `ConstraintRuleValidator`，不是「仅作提示」的假选项。
 */

/** 数值区间类约束模板（基线块4/5） */
export interface HardRulePreset {
  key: string
  /** 一句话说明，直接下拉展示 */
  label: string
  /** 约束 params.field，必须是计算层能算的字段 */
  field: string
  min?: number
  max?: number
  /** HARD 淘汰 / SOFT 扣分 */
  severity: 'HARD' | 'SOFT'
  /** 人类可读判定式（红线 R2：不允许存在用户看不到的规则） */
  humanReadable: string
  /** 为什么推荐这条 —— 让人判断该不该用，而不是盲选 */
  why: string
}

/**
 * 小分子常用硬约束。
 *
 * 取值来自 Lipinski 五规则、Veber 规则与常见 PAINS/BRENK 过滤口径；
 * 每条都带 `why`，因为「分子量 150~600」在不同项目里可能完全不同。
 */
export const HARD_RULE_PRESETS: HardRulePreset[] = [
  {
    key: 'mw',
    label: '分子量 150~600 Da',
    field: 'MolWt',
    min: 150,
    max: 600,
    severity: 'HARD',
    humanReadable: '分子量 150~600 Da',
    why: '低于 150 往往缺乏特异性，高于 600 口服吸收与合成难度都会上升'
  },
  {
    key: 'logp',
    label: 'LogP −0.4~5.0',
    field: 'LogP',
    min: -0.4,
    max: 5,
    severity: 'HARD',
    humanReadable: 'LogP 在 −0.4~5.0 之间',
    why: 'Lipinski 经典区间：过低难透过膜，过高容易非特异性结合与代谢负担'
  },
  {
    key: 'tpsa',
    label: '极性表面积 ≤ 140 Ų',
    field: 'TPSA',
    max: 140,
    severity: 'HARD',
    humanReadable: '极性表面积 TPSA ≤ 140 Ų',
    why: 'Veber 规则：TPSA 过大显著降低口服生物利用度'
  },
  {
    key: 'hbd',
    label: '氢键供体 ≤ 5',
    field: 'HBD',
    max: 5,
    severity: 'HARD',
    humanReadable: '氢键供体数 ≤ 5',
    why: 'Lipinski 五规则之一'
  },
  {
    key: 'hba',
    label: '氢键受体 ≤ 10',
    field: 'HBA',
    max: 10,
    severity: 'HARD',
    humanReadable: '氢键受体数 ≤ 10',
    why: 'Lipinski 五规则之一'
  },
  {
    key: 'rot',
    label: '可旋转键 ≤ 10',
    field: 'RotatableBonds',
    max: 10,
    severity: 'HARD',
    humanReadable: '可旋转键数 ≤ 10',
    why: 'Veber 规则：柔性过大不利于口服吸收与靶点结合熵'
  },
  {
    key: 'pains',
    label: 'PAINS 命中 = 0',
    field: 'PAINS',
    max: 0,
    severity: 'HARD',
    humanReadable: '不得命中 PAINS 结构警示',
    why: 'PAINS 是常见的假阳性来源，命中的分子在筛选中优先排除'
  },
  {
    key: 'brenk',
    label: 'BRENK 命中 ≤ 1',
    field: 'BRENK',
    max: 1,
    severity: 'SOFT',
    humanReadable: 'BRENK 结构警示命中数 ≤ 1',
    why: 'BRENK 覆盖面比 PAINS 更广但更宽松，做成软约束（扣分而非淘汰）更合适'
  },
  {
    key: 'qed',
    label: 'QED ≥ 0.5（软）',
    field: 'QED',
    min: 0.5,
    severity: 'SOFT',
    humanReadable: '类药性 QED ≥ 0.5',
    why: 'QED 是综合类药性打分，做软约束用于排序而非硬性淘汰'
  }
]

/** 风险关注点模板（基线块8） */
export interface RiskPreset {
  key: string
  /** 风险码（会写进 project_risk_item.risk_code） */
  riskCode: string
  /** 展示名 */
  label: string
  /** 关联维度 */
  dimension: string
  /** 默认说明，可改 */
  description: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  /** 是否必须实测验证 */
  mustVerify: boolean
  /** 默认勾选的理由 */
  why: string
}

/**
 * 小分子常见风险关注点。
 *
 * `mustVerify` 的取值有意克制：标成「必须验证」的风险点会在决策阶段变成 BLOCK 级
 * 否决（该风险没有实测证据 → 候选不得入选），全勾上等于什么都选不出来。
 * 因此只有「不看这个就不知道该不该推进」的几项默认为 true。
 */
export const RISK_PRESETS: RiskPreset[] = [
  {
    key: 'hepatotox',
    riskCode: 'HEPATOTOXICITY',
    label: '肝毒性',
    dimension: 'SAFETY',
    description: '肝毒性风险：关注结构警示与体外肝细胞活性',
    severity: 'HIGH',
    mustVerify: false,
    why: '肝毒性是临床前淘汰的首要原因之一；默认不强制实测，避免早期把候选全部卡死'
  },
  {
    key: 'herg',
    riskCode: 'CARDIO_SAFETY',
    label: '心脏安全性（hERG）',
    dimension: 'SAFETY',
    description: 'hERG 抑制与 QT 延长风险',
    severity: 'HIGH',
    mustVerify: false,
    why: '心血管安全性是 IND 申报必查项'
  },
  {
    key: 'cyp',
    riskCode: 'CYP_INHIBITION',
    label: 'CYP 酶抑制',
    dimension: 'PK',
    description: 'CYP450 抑制导致药物相互作用风险',
    severity: 'MEDIUM',
    mustVerify: false,
    why: '影响联合用药与代谢清除，是 DDI 预测的输入之一'
  },
  {
    key: 'plasma',
    riskCode: 'PLASMA_STABILITY',
    label: '血浆稳定性',
    dimension: 'STABILITY',
    description: '血浆/血清中被快速降解的风险',
    severity: 'MEDIUM',
    mustVerify: true,
    why: '半衰期算不出来的领域必须靠实测；不验证就等于拿推测值往下走'
  },
  {
    key: 'ptm',
    riskCode: 'PTM_HOTSPOT',
    label: 'PTM 修饰热点',
    dimension: 'STABILITY',
    description: '序列/结构中存在易被修饰的位点（糖基化、脱酰胺、氧化）',
    severity: 'MEDIUM',
    mustVerify: false,
    why: '修饰会改变活性与一致性，但通常先算再决定是否实测'
  },
  {
    key: 'aggregation',
    riskCode: 'AGGREGATION_RISK',
    label: '聚集风险',
    dimension: 'DEVELOPABILITY',
    description: '高浓度下易聚集，影响制剂与给药',
    severity: 'MEDIUM',
    mustVerify: false,
    why: '聚集与黏度直接决定可制剂性'
  },
  {
    key: 'viscosity',
    riskCode: 'VISCOSITY_RISK',
    label: '高浓度黏度',
    dimension: 'DEVELOPABILITY',
    description: '高浓度制剂黏度过高，影响皮下给药',
    severity: 'MEDIUM',
    mustVerify: false,
    why: '皮下给药产品必须过这一关'
  },
  {
    key: 'immuno',
    riskCode: 'IMMUNOGENICITY',
    label: '免疫原性',
    dimension: 'IMMUNOGENICITY',
    description: '可能引发抗药抗体（ADA）的风险',
    severity: 'HIGH',
    mustVerify: false,
    why: '生物药的核心风险；小分子通常不需要'
  },
  {
    key: 'selectivity',
    riskCode: 'SELECTIVITY_RISK',
    label: '选择性不足',
    dimension: 'AFFINITY',
    description: '对同家族其他靶点存在明显结合，脱靶风险',
    severity: 'MEDIUM',
    mustVerify: false,
    why: '选择性差会带来副作用，也影响后续开发窗口'
  },
  {
    key: 'synthesis',
    riskCode: 'SYNTHESIS_FEASIBILITY',
    label: '合成可行性',
    dimension: 'DEVELOPABILITY',
    description: '合成路线过长或关键步骤收率低',
    severity: 'MEDIUM',
    mustVerify: false,
    why: '合成不出来的分子再好的活性也没有意义'
  }
]

/** 按风险码查展示名（已有风险点回显用） */
export function riskPresetOf(code: string): RiskPreset | undefined {
  return RISK_PRESETS.find((r) => r.riskCode === code)
}

/**
 * 可写进 `params.field` 的字段。
 *
 * 只列 `MetricFieldMapper` 真能映射的 13 个 —— 列多了用户会写出「提交成功但生成时
 * 记 SKIPPED」的约束，这是最难排查的一类问题。
 */
export const FIELD_OPTIONS = [
  { value: 'MolWt', label: '分子量', unit: 'Da' },
  { value: 'LogP', label: '脂水分配系数 LogP', unit: '' },
  { value: 'TPSA', label: '极性表面积', unit: 'Ų' },
  { value: 'HBD', label: '氢键供体数', unit: '个' },
  { value: 'HBA', label: '氢键受体数', unit: '个' },
  { value: 'RotatableBonds', label: '可旋转键数', unit: '个' },
  { value: 'Rings', label: '环数', unit: '个' },
  { value: 'QED', label: '类药性 QED', unit: '' },
  { value: 'FractionCSP3', label: 'sp³ 碳比例', unit: '' },
  { value: 'Lipinski', label: 'Lipinski 通过标记', unit: '' },
  { value: 'PAINS', label: 'PAINS 命中数', unit: '个' },
  { value: 'BRENK', label: 'BRENK 命中数', unit: '个' }
] as const

export function fieldLabel(field: string): string {
  return FIELD_OPTIONS.find((f) => f.value === field)?.label ?? field
}

/** 由字段与区间拼一句人话，省得用户自己想 humanReadable 怎么写 */
export function describeRange(field: string, min?: number | null, max?: number | null): string {
  const name = fieldLabel(field)
  const hasMin = min !== undefined && min !== null
  const hasMax = max !== undefined && max !== null
  if (hasMin && hasMax) return `${name} 在 ${min}~${max} 之间`
  if (hasMin) return `${name} ≥ ${min}`
  if (hasMax) return `${name} ≤ ${max}`
  return name
}

/**
 * 把模板转成约束规则请求体。
 *
 * `params` 结构（`{field, min, max}`）与 `ConstraintRuleValidator` 的校验口径一致 ——
 * 少一个 `field` 或 min>max 都会被后端挡回来，这里直接给对。
 */
export function toRuleBody(preset: HardRulePreset, index: number) {
  const params: Record<string, unknown> = { field: preset.field }
  if (preset.min !== undefined && preset.min !== null) params.min = preset.min
  if (preset.max !== undefined && preset.max !== null) params.max = preset.max
  return {
    ruleType: 'NUMERIC_RANGE',
    severity: preset.severity,
    params,
    humanReadable: preset.humanReadable,
    // SOFT 规则需要权重参与扣分；HARD 不需要
    weight: preset.severity === 'SOFT' ? 1 : undefined,
    sortNo: index,
    enabled: true
  }
}
