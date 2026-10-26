/**
 * 需求分析（阶段 B）· 接口门面
 * ============================================================================
 * 与 `design.ts` 的区别：这套接口**只有真实实现**，没有 mock。
 * 原因：需求基线是「八块不齐不得冻结」的强约束流程，用假数据演示反而会让人
 * 以为缺口可以被绕过 —— 而这正是本阶段要防的事。
 *
 * 端点与 `synpharm-deploy/docs/design/detailed/06-主业务三阶段设计方案.md` §3 一致。
 */
import { request } from '@/utils/request'

/** 八块的机器码，顺序与后端 `BaselineBlock` 的声明顺序一致 */
export type BaselineBlockCode =
  | 'TARGETS'
  | 'STRUCTURE'
  | 'MODALITY'
  | 'HARD_RULES'
  | 'SOFT_RULES'
  | 'METRICS'
  | 'QUOTA'
  | 'RISKS'

/** 块状态：MISSING 阻断冻结，WARN 可冻结但有风险 */
export type BaselineBlockState = 'OK' | 'WARN' | 'MISSING'

export interface BaselineBlockStatus {
  code: BaselineBlockCode
  label: string
  required: boolean
  state: BaselineBlockState
  message?: string
}

export interface BaselineGap {
  block: BaselineBlockCode
  /** 机器可读码：NO_TARGET / NO_STRUCTURE / NO_METRIC / NO_MIN_CANDIDATES … */
  code: string
  /** 直接展示的中文说明（后端保证可读，不做隐藏规则） */
  message: string
  severity: 'ERROR' | 'WARN'
}

/** 预检结果。不落库，只回答「现在冻结会缺什么」 */
export interface BaselinePreview {
  ready: boolean
  blocks: BaselineBlockStatus[]
  gaps: BaselineGap[]
  /** 当前内容的 sha256；冻结时回传可挡住「预检后别人又改过」 */
  contentHash: string
}

/** 已冻结的快照（八块内容原样回放） */
export interface BaselineSnapshot {
  id: number
  snapshotNo: string
  projectId: number
  projectVersion: number
  userId: number
  targets: Record<string, unknown> | null
  structure: Record<string, unknown> | null
  modalityCode: string | null
  hardRules: Record<string, unknown> | null
  softRules: Record<string, unknown> | null
  metrics: Record<string, unknown> | null
  quota: Record<string, unknown> | null
  risks: Record<string, unknown> | null
  completeness: Record<string, BaselineBlockState> | null
  contentHash: string
  status: 'ACTIVE' | 'SUPERSEDED'
  supersededBy: number | null
  createdAt: string
}

/** 项目指标（基线块6） */
export interface ProjectMetric {
  id: number
  projectId: number
  metricCode: string
  dimension?: string
  metricName?: string
  unit?: string
  /** HIGHER / LOWER */
  better?: string
  weight: number
  targetValue?: number | null
  threshold?: number | null
  operator?: string | null
  tolerance?: number | null
  /** STRICT / WITHIN_TOLERANCE / ESCALATE_TO_SOFT */
  boundaryPolicy?: string | null
  priority?: number
  enabled?: boolean
  /** 人类可读判定式，如「≤ 10 nM」 */
  boundaryText?: string
}

export interface ProjectMetricUpsert {
  metricCode?: string
  dimension?: string
  weight?: number
  targetValue?: number | null
  threshold?: number | null
  operator?: string | null
  tolerance?: number | null
  boundaryPolicy?: string | null
  priority?: number
  enabled?: boolean
}

/** 风险关注点（基线块8） */
export interface ProjectRisk {
  id: number
  projectId: number
  riskCode: string
  dimension?: string
  description?: string
  severity?: string
  /** true 时决策阶段强制要求实测证据 */
  mustVerify: boolean
  source?: string
}

export interface ProjectRiskUpsert {
  riskCode?: string
  dimension?: string
  description?: string
  severity?: string
  mustVerify?: boolean
  source?: string
}

/** 项目上的配额与作用机制（基线块1/7 的写入位） */
export interface ProjectQuotaPatch {
  moaCode?: string
  minCandidates?: number
  quotaGpuHours?: number
  quotaWetLab?: number
  /** 决策名额档位：RATIO=按候选数比例 / ABSOLUTE=固定个数 */
  decisionCapacityMode?: string
  /** RATIO 时 0~1，ABSOLUTE 时正整数；最终仍受湿实验余额封顶 */
  decisionCapacityValue?: number
}

/** 项目详情里本页需要的字段（其余字段本页不关心） */
export interface ProjectBrief {
  id: number
  projectNo: string
  name: string
  goalText?: string
  modalityId?: number
  moaCode?: string | null
  minCandidates?: number | null
  quotaGpuHours?: number | null
  quotaGpuUsed?: number | null
  quotaWetLab?: number | null
  quotaWetUsed?: number | null
  decisionCapacityMode?: string | null
  decisionCapacityValue?: number | null
  currentBaselineId?: number | null
  projectVersion?: number | null
  activeConstraintSetId?: number | null
  /** 当前生效轮次；为空说明还没开轮次，生成任务无法挂载 */
  currentRoundId?: number | null
  status: string
  readyBlockers?: string[]
  targets?: Array<{ targetId: number; role: string; isPrimary: boolean; name?: string }>
  /** 候选统计（列表页用来显示「这个项目已经跑到哪一步」） */
  candidateStats?: {
    total?: number
    qualified?: number
    top?: number
  }
  createdAt?: string
}

/** 知识库条目（驱动 MOA / 分子形式下拉） */
export interface LibraryItem {
  id: number
  category: string
  code: string
  name: string
  enabled?: boolean
}

/** 靶点简要信息（建项目时选主靶点用） */
export interface TargetBrief {
  id: number
  name: string
  geneName?: string
  chineseName?: string
}

/** 建项目请求（只列本页会用到的字段，其余走默认） */
export interface ProjectCreateBody {
  name: string
  modalityId: number
  moaCode?: string
  minCandidates?: number
  quotaGpuHours?: number
  quotaWetLab?: number
  targets: Array<{ targetId: number; role: string; isPrimary: boolean }>
  constraintRules?: Array<{
    ruleType: string
    severity: string
    params: Record<string, unknown>
    weight?: number
    humanReadable?: string
  }>
}

const base = (projectId: number) => `/api/design/projects/${projectId}`

export const requirementApi = {
  /** 项目列表（阶段 A~D 各页的入口：都需要先知道 projectId） */
  listProjects: (page = 1, pageSize = 50) =>
    request.get<{ list: ProjectBrief[]; total: number; page: number; pageSize: number; hasNext: boolean }>(
      '/api/design/projects',
      { params: { page, pageSize } }
    ),

  /** 靶点分页（建项目时挑主靶点） */
  listTargets: (page = 1, pageSize = 20) =>
    request.get<{ list: TargetBrief[]; total: number }>('/api/design/targets', {
      params: { page, pageSize }
    }),

  /** 建项目（targets 至少一条且恰好一个 isPrimary） */
  createProject: (body: ProjectCreateBody) => request.post<{ id: number }>('/api/design/projects', body),

  /** 开启一轮：生成任务必须挂在某一轮上 */
  openRound: (projectId: number, strategyNote?: string) =>
    request.post<{ id: number }>(`${base(projectId)}/rounds`, { strategyNote }),

  /** 项目详情：拿配额、机制、当前基线指针 */
  getProject: (projectId: number) => request.get<ProjectBrief>(base(projectId)),

  /** 知识库分页检索（category=MOA / MODALITY） */
  listLibrary: (category: string, pageSize = 100) =>
    request.get<{ list: LibraryItem[]; total: number }>('/api/design/library', {
      params: { category, page: 1, pageSize, enabled: true }
    }),

  /** 逐块预检八块；不落库 */
  preview: (projectId: number) =>
    request.post<BaselinePreview>(`${base(projectId)}/baseline:preview`),

  /**
   * 冻结基线。
   * `expectedContentHash` 传预检拿到的 hash：预检后项目被改过会返回 409 BASELINE_STALE。
   * 传空则跳过并发校验（首次提交的常规路径）。
   */
  freeze: (projectId: number, expectedContentHash?: string) =>
    request.post<BaselineSnapshot>(`${base(projectId)}/baseline:freeze`, {
      expectedContentHash
    }),

  /** 当前 ACTIVE 快照；没冻结过返回 404 BASELINE_NOT_FOUND */
  current: (projectId: number) => request.get<BaselineSnapshot>(`${base(projectId)}/baseline`),

  /** 新→旧全量版本历史 */
  history: (projectId: number) =>
    request.get<BaselineSnapshot[]>(`${base(projectId)}/baseline/history`),

  listMetrics: (projectId: number) => request.get<ProjectMetric[]>(`${base(projectId)}/metrics`),

  addMetric: (projectId: number, body: ProjectMetricUpsert) =>
    request.post<ProjectMetric>(`${base(projectId)}/metrics`, body),

  updateMetric: (projectId: number, metricId: number, body: ProjectMetricUpsert) =>
    request.put<ProjectMetric>(`${base(projectId)}/metrics/${metricId}`, body),

  deleteMetric: (projectId: number, metricId: number) =>
    request.delete<void>(`${base(projectId)}/metrics/${metricId}`),

  /** 按分子形式补齐指标：只补缺失的，不覆盖用户手调过的权重 */
  initMetricsFromModality: (projectId: number) =>
    request.post<ProjectMetric[]>(`${base(projectId)}/metrics:init-from-modality`),

  listRisks: (projectId: number) => request.get<ProjectRisk[]>(`${base(projectId)}/risks`),

  addRisk: (projectId: number, body: ProjectRiskUpsert) =>
    request.post<ProjectRisk>(`${base(projectId)}/risks`, body),

  updateRisk: (projectId: number, riskId: number, body: ProjectRiskUpsert) =>
    request.put<ProjectRisk>(`${base(projectId)}/risks/${riskId}`, body),

  deleteRisk: (projectId: number, riskId: number) =>
    request.delete<void>(`${base(projectId)}/risks/${riskId}`),

  /** 配额与作用机制走项目编辑接口（PATCH：给出才改） */
  patchProject: (projectId: number, body: ProjectQuotaPatch) =>
    request.patch<{ id: number }>(base(projectId), body)
}

/** 块状态 → 展示元数据 */
export function blockStateMeta(state?: BaselineBlockState): {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'neutral'
} {
  switch (state) {
    case 'OK':
      return { label: '已齐', tone: 'success' }
    case 'WARN':
      return { label: '有告警', tone: 'warning' }
    case 'MISSING':
      return { label: '缺失', tone: 'danger' }
    default:
      return { label: '未预检', tone: 'neutral' }
  }
}

/** 边界策略 → 中文。三选一是必须显式选择的，不能有默认值 */
export const BOUNDARY_POLICY_OPTIONS = [
  { value: 'STRICT', label: '严格', hint: '越界即淘汰' },
  { value: 'WITHIN_TOLERANCE', label: '容差内', hint: '落在容差带算达标，但打警告进证据包' },
  { value: 'ESCALATE_TO_SOFT', label: '交给决策', hint: '不判硬性达标，转软约束扣分' }
] as const
