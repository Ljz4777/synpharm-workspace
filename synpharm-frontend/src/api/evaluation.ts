/**
 * 最小评估 + 编辑（阶段 D）· 接口门面
 * ============================================================================
 * 与 `requirement.ts` / `generation.ts` 同一口径：**只有真实实现，没有 mock**。
 * 评估与编辑是「候选能不能进决策」的闸门，用假分数演示会让人以为
 * 92 分是完整结论 —— 而它其实只代表三分之一的指标。
 *
 * 端点与 `synpharm-deploy/docs/design/detailed/06-主业务三阶段设计方案.md` §5.7 一致。
 */
import { request } from '@/utils/request'

// ---------------------------------------------------------------- 评估

/** 单个指标的评分：value 为 null 表示算不出来（不是 0） */
export interface MetricScore {
  value?: number | null
  normalized?: number | null
  /** 未配置判定阈值时为 null —— 「无法判定」不等于「不达标」 */
  passed?: boolean | null
  risk?: string | null
  note?: string | null
}

export interface EvaluationFreeze {
  id: number
  freezeNo: string
  projectId: number
  candidateId: number
  versionNo: number
  evalRunId?: number | null
  scores: Record<string, MetricScore>
  hardPassed: boolean
  totalScore?: number | null
  /**
   * 可算权重占比 0~1。
   * 小分子目前只有约三分之一的权重能算出值 —— totalScore 只代表这部分。
   */
  weightCoverage?: number | null
  /** 权重里有但算不出来的指标 */
  missingMetrics: string[]
  /** 未能判定的规则码（没检查 ≠ 检查通过） */
  skippedRules: string[]
  violations: string[]
  engineVersions?: Record<string, unknown> | null
  calibrationId?: string | null
  contentHash: string
  createdAt?: string
}

// ---------------------------------------------------------------- 缺口（E1）

export type GapDifficulty = 'EASY' | 'MEDIUM' | 'HARD' | 'UNFIXABLE'
export type GapType = 'THRESHOLD_NOT_MET' | 'NOT_EVALUATED'

export interface EditGap {
  metricCode: string
  dimension: string
  gapType: GapType | string
  value?: number | null
  threshold?: number | null
  /** 归因恒为 null：需要结构归因模型，当前未接入 */
  attribution?: string | null
  attributionNote?: string | null
  editable: boolean
  difficulty: GapDifficulty | string
  reason: string
}

/** E2 匹配出的候选动作 */
export interface EditActionOption {
  gapDimension: string
  actionCode: string
  isFallback: boolean
  successRate?: number | null
  sampleSize: number
  sideEffects?: Record<string, unknown> | null
  /** sampleSize=0 时成功率为估计值，界面必须标出来 */
  rateIsEstimate: boolean
}

export interface EditGapReport {
  id: number
  reportNo: string
  candidateId: number
  versionNo: number
  evalFreezeId: number
  gaps: EditGap[]
  /** 已达标维度 —— 保护名单，E3 回归守卫的输入 */
  noGaps: string[]
  unfixable: EditGap[]
  recommendedOrder: string[]
  note?: string | null
  actions: EditActionOption[]
  attributionNote?: string
}

// ---------------------------------------------------------------- 影响面（E3）

export interface ProtectedImpact {
  dimension: string
  /** MUST_DROP / WATCH */
  risk: string
  currentPassed: boolean
  /** 恒为 null：动作→指标的量化模型未接入 */
  predictedDelta?: number | null
  note?: string
}

export interface EditImpact {
  id?: number
  candidateId: number
  actionCode: string
  /** PASS / WARN / BLOCK */
  guardResult: string
  blockReason?: string | null
  protectedOnes: ProtectedImpact[]
  executable: boolean
  note?: string
}

// ---------------------------------------------------------------- 版本树

export interface CandidateTreeNode {
  id: number
  parentId?: number | null
  versionNo?: number
  editRound?: number
  branchStatus: string
  pipelineStatus?: string
  originType?: string
  strategyName?: string
  smiles?: string | null
  sequenceHash?: string | null
  totalScore?: number | null
  hardPassed?: boolean
  hasFreeze?: boolean
  needsReEval?: number
  isFocus?: boolean
  depth?: number
  note?: string | null
  createdAt?: string
}

export interface CandidateTree {
  rootId: number
  focusId: number
  nodes: CandidateTreeNode[]
}

// ---------------------------------------------------------------- API

export const evaluationApi = {
  /** 评估并冻结（幂等：同候选 + 同版本 + 同内容哈希返回已有快照） */
  freeze: (candidateId: number, baselineSnapshotId?: number) =>
    request.post<EvaluationFreeze>(
      `/api/design/candidates/${candidateId}/evaluation:freeze`,
      { baselineSnapshotId }
    ),

  latest: (candidateId: number) =>
    request.get<EvaluationFreeze>(`/api/design/candidates/${candidateId}/evaluation`),

  history: (candidateId: number) =>
    request.get<EvaluationFreeze[]>(`/api/design/candidates/${candidateId}/evaluation/history`)
}

export const editApi = {
  /** E0+E1：诊断缺口（准入 5 项不过直接拒绝，不给默认值） */
  diagnose: (candidateId: number) =>
    request.post<EditGapReport>(`/api/design/candidates/${candidateId}/gaps:diagnose`),

  gaps: (candidateId: number) =>
    request.get<EditGapReport>(`/api/design/candidates/${candidateId}/gaps`),

  /** E3：影响面评估（BLOCK 时 executable=false） */
  previewImpact: (candidateId: number, actionCode: string, targetRegion?: string) =>
    request.post<EditImpact>(`/api/design/candidates/${candidateId}/actions:preview`, {
      actionCode,
      targetRegion
    }),

  tree: (candidateId: number) =>
    request.get<CandidateTree>(`/api/design/candidates/${candidateId}/tree`),

  /** 放弃分支：只改 branch_status，不删除 */
  abandon: (candidateId: number) =>
    request.post<{ candidateId: number; branchStatus: string; note: string }>(
      `/api/design/candidates/${candidateId}:abandon`
    ),

  /** 切回父版本 = 新建与父同内容的新版本（历史只增不删） */
  revert: (candidateId: number) =>
    request.post<{ candidateId: number; sourceCandidateId: number }>(
      `/api/design/candidates/${candidateId}:revert`
    )
}

// ---------------------------------------------------------------- 展示元数据

export function guardMeta(result?: string): {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'neutral'
  hint: string
} {
  switch (result) {
    case 'PASS':
      return { label: '可执行', tone: 'success', hint: '未发现会影响已达标维度的副作用' }
    case 'WARN':
      return { label: '告警可执行', tone: 'warning', hint: '可能影响某些维度，改完必须复核' }
    case 'BLOCK':
      return { label: '已拦截', tone: 'danger', hint: '会打坏已达标维度，不能拿已到手的东西去换' }
    default:
      return { label: '未评估', tone: 'neutral', hint: '' }
  }
}

export function difficultyMeta(difficulty?: string): {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'neutral'
} {
  switch (difficulty) {
    case 'EASY':
      return { label: '易修', tone: 'success' }
    case 'MEDIUM':
      return { label: '中等', tone: 'warning' }
    case 'HARD':
      return { label: '难修', tone: 'danger' }
    case 'UNFIXABLE':
      return { label: '修不了', tone: 'neutral' }
    default:
      return { label: difficulty || '未知', tone: 'neutral' }
  }
}

/** 缺口维度 → 中文 */
export function dimensionLabel(dimension: string): string {
  const MAP: Record<string, string> = {
    AFFINITY: '结合力',
    AGGREGATION: '聚集风险',
    EXPRESSION: '表达量',
    IMMUNOGENICITY: '免疫原性',
    SELECTIVITY: '选择性',
    STABILITY: '稳定性',
    SYNTHESIZABILITY: '可合成性',
    DEVELOPABILITY: '可开发性',
    SAFETY: '安全性',
    NOVELTY: '新颖性',
    UNMAPPED: '未归类'
  }
  return MAP[dimension] || dimension
}

/** 动作码 → 中文。未收录原样展示，不吞掉 */
export function actionLabel(code: string): string {
  const MAP: Record<string, string> = {
    MUTATION: '定点突变',
    INTERFACE_REDESIGN: '界面重设计',
    SIDE_CHAIN: '侧链改造',
    HUMANIZATION: '人源化',
    LINKER: '连接子优化',
    GLYCO_OPT: '糖基化优化',
    SCAFFOLD_HOP: '骨架跃迁'
  }
  return MAP[code] || code
}
