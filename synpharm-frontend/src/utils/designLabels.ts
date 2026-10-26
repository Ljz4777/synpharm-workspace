/**
 * 设计域枚举 → 中文。
 *
 * 为什么单独抽一个文件：这些码在「八块看板」「补齐向导」「版本历史」「精修台」
 * 都要显示，而页面直接展示 `HARD_RULES:MISSING`、`SMALL_MOLECULE` 这类机器码
 * 对用户等于没说。之前每个组件各抄一份映射，漏抄的地方就退回成原始码。
 */

/** 基线八块 */
export const BLOCK_LABEL: Record<string, string> = {
  TARGETS: '靶点',
  STRUCTURE: '结构',
  MODALITY: '分子形式',
  HARD_RULES: '硬约束',
  SOFT_RULES: '软约束',
  METRICS: '指标',
  QUOTA: '配额',
  RISKS: '风险'
}

/** 块状态：OK 齐全 / WARN 可冻结但有告警 / MISSING 阻断冻结 */
export const BLOCK_STATE_LABEL: Record<string, string> = {
  OK: '齐全',
  WARN: '有告警',
  MISSING: '缺失'
}

/** 分子形式 */
export const MODALITY_LABEL: Record<string, string> = {
  SMALL_MOLECULE: '小分子',
  ANTIBODY: '抗体',
  PEPTIDE: '多肽',
  BISPECIFIC: '双特异性抗体',
  ADC: '抗体偶联药物',
  FUSION_PROTEIN: '融合蛋白',
  NANOBODY: '纳米抗体'
}

/** 作用机制 */
export const MOA_LABEL: Record<string, string> = {
  INHIBITOR: '抑制剂',
  AGONIST: '激动剂',
  ANTAGONIST: '拮抗剂',
  DEGRADER: '降解剂',
  BRIDGING: '桥连（双抗）'
}

/** 靶点在项目里的角色 */
export const TARGET_ROLE_LABEL: Record<string, string> = {
  WILD_TYPE: '野生型',
  MUTANT: '突变体',
  ANTI_TARGET: '反靶点',
  OFF_TARGET: '脱靶'
}

/** 约束规则类型 */
export const RULE_TYPE_LABEL: Record<string, string> = {
  NUMERIC_RANGE: '数值区间'
}

/** 阈值越界时的处理方式 */
export const BOUNDARY_POLICY_LABEL: Record<string, string> = {
  STRICT: '严格',
  WITHIN_TOLERANCE: '容许误差',
  ESCALATE_TO_SOFT: '降为软约束'
}

/** 指标方向 */
export const OPERATOR_LABEL: Record<string, string> = {
  GTE: '≥',
  LTE: '≤',
  GT: '>',
  LT: '<',
  EQ: '='
}

export const BETTER_LABEL: Record<string, string> = {
  HIGHER: '越高越好',
  LOWER: '越低越好'
}

/** 决策名额档位 */
export const CAPACITY_MODE_LABEL: Record<string, string> = {
  RATIO: '按候选数比例',
  ABSOLUTE: '固定个数'
}

/** 决策分桶。三个桶的差别是「靠什么通过」，不是「分数高低」 */
export const BUCKET_LABEL: Record<string, string> = {
  FULL_PASS: '全通过',
  TOLERANCE: '误差内通过',
  FAIL: '未通过'
}

export const BUCKET_HINT: Record<string, string> = {
  FULL_PASS: '硬约束全部达标，且没有触发任何否决规则',
  TOLERANCE: '硬约束落在容许误差内，可以进入排名，但需要人工确认',
  FAIL: '硬约束不达标或被 BLOCK 规则否决 —— 只能改数据重来，不接受绕过'
}

/** 候选去向 */
export const OUTCOME_LABEL: Record<string, string> = {
  SELECTED: '选中',
  BACKUP: '备选',
  REJECTED: '淘汰',
  DEFERRED: '挂起'
}

/** 决策记录状态 */
export const DECISION_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  DEFERRED: '已挂起',
  CANCELLED: '已取消'
}

/**
 * 决策阶段淘汰原因。区分「该换分子」与「该补数据」是关键：
 * 都是淘汰，但下一步动作完全相反。
 */
export const FILTER_REASON_LABEL: Record<string, string> = {
  VETO_MUST_VERIFY_MISSING: '必须验证的风险还没验证（该补实验，不是换分子）',
  VETO_BUDGET_CUT: '名额与湿实验余额不够，排名靠后未进名额',
  VETO_FTO_RISK: '自由实施（FTO）风险过高',
  VETO_DATA_INCOMPLETE: '证据不全，无法判定（该补数据）',
  VETO_HARD_RECHECK_FAILED: '硬约束复核不达标（该换分子）',
  VETO_MANUAL: '人工否决'
}

/** 六条内置否决规则 */
export const VETO_RULE_LABEL: Record<string, string> = {
  MUST_VERIFY_UNVERIFIED: '存在必须实测但未验证的风险项',
  BUDGET_EXCEEDED: '超出项目预算',
  FTO_RISK_HIGH: '自由实施风险过高',
  DATA_INCOMPLETE: '证据不全',
  HARD_RULE_VIOLATED: '硬约束被违反',
  MANUAL_VETO: '人工否决'
}

export const SEVERITY_LABEL: Record<string, string> = {
  BLOCK: '一票否决',
  WARN: '告警'
}

/** 查表，未命中时原样返回码本身（而不是显示 undefined） */
export function labelOf(map: Record<string, string>, code?: string | null): string {
  if (!code) return '—'
  return map[code] ?? code
}
