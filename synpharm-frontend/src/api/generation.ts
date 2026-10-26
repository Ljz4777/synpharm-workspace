/**
 * 生成（阶段 C）· 接口门面
 * ============================================================================
 * 与 `requirement.ts` 同一口径：**只有真实实现，没有 mock**。
 * 生成是「候选是否可信」的源头，用假数据演示会让 degraded/筛除原因这些
 * 关键信息看起来像装饰 —— 而它们恰恰是这套流程唯一的价值。
 *
 * 端点与 `synpharm-deploy/docs/design/detailed/06-主业务三阶段设计方案.md` §4.6 一致。
 */
import { request } from '@/utils/request'

// ---------------------------------------------------------------- 枚举

/** 生成批次状态。PENDING 是刚登记，SUCCEEDED 才算跑完 */
export type GenerationStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'

/** 候选来源：ENGINE 是模型生成（当前未部署），另两个是规则/导入 */
export type GenerationStrategy = 'SEED_LIBRARY' | 'SEQUENCE_MUTATION' | 'ENGINE'

/** S9 结论。判定由后端代码算，前端只负责翻译与配色 */
export type GenerationVerdict = 'PASS' | 'PASS_WITH_WARNING' | 'FAIL'

/** 逐分子日志的阶段码 */
export type GenerationStage =
  | 'S5_DEDUP'
  | 'S6_PRE_FILTER'
  | 'S7_QUOTA'

/** 逐分子日志的结局码 */
export type GenerationOutcome = 'KEPT' | 'FILTERED' | 'MERGED' | 'QUOTA_CUT'

// ---------------------------------------------------------------- 提交

export interface GenerationSeedInput {
  /** 调用方标识，用于日志追溯 */
  ref?: string
  /** 小分子：SMILES */
  smiles?: string
  /** 抗体/多肽：重链 */
  heavyChain?: string
  /** 抗体/多肽：轻链 */
  lightChain?: string
  /** 双抗等额外链 */
  extraChains?: string[]
}

export interface GenerationCreateRequest {
  /** 为空取项目当前 ACTIVE 快照 */
  baselineSnapshotId?: number
  /**
   * 幂等键。同键重复提交返回同一批次，不产生第二份候选集。
   * 为空表示不做幂等。
   */
  idempotencyKey?: string
  /** 为空则由后端随机生成并写回 run（可凭它复现） */
  seed?: number
  /** 为空则自动取项目内已有候选当父本 */
  seeds?: GenerationSeedInput[]
}

// ---------------------------------------------------------------- 响应

export interface GenerationSliceView {
  sliceNo: number
  status: string
  producedCount?: number
  retryCount?: number
  seed?: number
  errorMsg?: string
  completedAt?: string
}

/** 漏斗四段 + 转化率。分母为 0 时率为 null（「没数据」≠「全灭」） */
export interface GenerationFunnel {
  generated: number
  afterDedup: number
  afterPreFilter: number
  afterQuota: number
  quotaCut: number
  dedupRate?: number | null
  preFilterPassRate?: number | null
  quotaKeepRate?: number | null
  overallYield?: number | null
  filterReasonTop?: Record<string, number> | null
}

export interface GenerationFinding {
  level: string
  message: string
  suggestion?: string
}

export interface GenerationDiagnosis {
  verdict: GenerationVerdict
  funnel: GenerationFunnel
  findings: GenerationFinding[]
  readyForEdit: boolean
  recommendedNext: string
}

export interface GenerationPlanTool {
  toolCode: string
  params?: Record<string, unknown>
}

/** S3 计划（原样回放，便于对着数字解释「为什么是这个采样量」） */
export interface GenerationPlan {
  strategy: string
  tools: GenerationPlanTool[]
  fallbackStrategy?: string
  nSamples: number
  batchSize: number
  seed: number
  estimatedGpuHours?: number
  rationale?: string
  confidence?: number
  degraded?: boolean
  degradedReason?: string
}

export interface GenerationRun {
  id: number
  runNo: string
  projectId: number
  roundId: number
  baselineSnapshotId?: number
  status: GenerationStatus
  strategy: GenerationStrategy | string

  /** 是否降级。true = 这批候选不是模型生成的 */
  degraded?: boolean
  degradedReason?: string

  nSamples?: number
  batchSize?: number
  seed?: number
  estimatedGpuHours?: number
  planConfidence?: number
  rationale?: string
  planHash?: string

  generatedCount?: number
  dedupedCount?: number
  rejectedCount?: number
  finalCount?: number
  validClusterCount?: number
  minCandidates?: number

  progress?: number
  errorMsg?: string
  latencyMs?: number
  outputSnapshotId?: number
  startedAt?: string
  completedAt?: string
  createdAt?: string

  plan?: GenerationPlan | null
  funnel?: GenerationFunnel | null
  diagnosis?: GenerationDiagnosis | null
  slices?: GenerationSliceView[]
}

/** 逐分子日志：「这个分子为什么没进候选池」 */
export interface GenerationCandidateLog {
  id: number
  stage: GenerationStage | string
  outcome: GenerationOutcome | string
  smiles?: string
  sequenceHash?: string
  inchikey?: string
  candidateId?: number
  /** 结构化原因，JSON 字符串（后端保证是数组） */
  filterReason?: string | null
  preScore?: number
  clusterId?: string
  dedupInto?: number
  createdAt?: string
}

/** 一条被解析出来的筛除原因 */
export interface FilterReasonItem {
  code: string
  message: string
  /** HARD 才是淘汰；WARN/INFO 只是提示 */
  severity: 'HARD' | 'WARN' | 'INFO' | string
}

// ---------------------------------------------------------------- API

const base = (projectId: number) => `/api/design/projects/${projectId}`

export const generationApi = {
  /** 提交并同步跑完 S0~S9（当前规则式是毫秒级，缺省视为同步返回） */
  submit: (projectId: number, body: GenerationCreateRequest) =>
    request.post<GenerationRun>(`${base(projectId)}/generations`, body),

  /**
   * 批次列表（新→旧）。
   *
   * 注意路径不是 `projects/{id}/generations`：提交在项目下（`POST projects/{id}/generations`），
   * 列表是全局集合资源再按 `projectId` 过滤 —— 两者不对称是后端既有契约，别照直觉改。
   */
  list: (projectId: number, page = 1, pageSize = 20) =>
    request.get<{ list: GenerationRun[]; total: number; page: number; pageSize: number; hasNext: boolean }>(
      '/api/design/generations',
      { params: { projectId, page, pageSize } }
    ),

  detail: (runId: number) => request.get<GenerationRun>(`/api/design/generations/${runId}`),

  /** 只要漏斗，省掉诊断与分片 */
  funnel: (runId: number) => request.get<GenerationRun>(`/api/design/generations/${runId}/funnel`),

  diagnosis: (runId: number) =>
    request.get<GenerationRun>(`/api/design/generations/${runId}/diagnosis`),

  /**
   * 逐分子日志。
   * @param stage 只看某个阶段；不传返回全部
   * @param outcome 只看某个结局；不传返回全部
   */
  candidateLogs: (runId: number, stage?: string, outcome?: string) =>
    request.get<GenerationCandidateLog[]>(`/api/design/generations/${runId}/candidates`, {
      params: { stage, outcome }
    }),

  cancel: (runId: number) => request.post<GenerationRun>(`/api/design/generations/${runId}:cancel`)
}

// ---------------------------------------------------------------- 展示元数据

export function verdictMeta(verdict?: GenerationVerdict): {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'neutral'
  hint: string
} {
  switch (verdict) {
    case 'PASS':
      return { label: '通过', tone: 'success', hint: '候选数与有效簇均达标，可进入编辑' }
    case 'PASS_WITH_WARNING':
      return {
        label: '带告警通过',
        tone: 'warning',
        hint: '允许进入编辑，但覆盖或数量不足，建议回 S2/S3 调整后再收敛'
      }
    case 'FAIL':
      return { label: '不通过', tone: 'danger', hint: '候选为空或全部分片失败，禁止往下游流' }
    default:
      return { label: '未判定', tone: 'neutral', hint: '批次尚未跑完' }
  }
}

export function statusMeta(status?: GenerationStatus): {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'neutral' | 'info'
} {
  switch (status) {
    case 'SUCCEEDED':
      return { label: '已完成', tone: 'success' }
    case 'PARTIAL':
      return { label: '部分失败', tone: 'warning' }
    case 'FAILED':
      return { label: '失败', tone: 'danger' }
    case 'CANCELLED':
      return { label: '已取消', tone: 'neutral' }
    case 'RUNNING':
      return { label: '运行中', tone: 'info' }
    case 'PENDING':
      return { label: '排队中', tone: 'neutral' }
    default:
      return { label: '未知', tone: 'neutral' }
  }
}

export function strategyMeta(strategy?: string): { label: string; hint: string } {
  switch (strategy) {
    case 'SEED_LIBRARY':
      return { label: '导入筛选', hint: '用户提供的分子直接进入筛选漏斗，不做结构创新' }
    case 'SEQUENCE_MUTATION':
      return { label: '单点突变', hint: '规则式单点突变衍生，未启用 CDR 保护' }
    case 'ENGINE':
      return { label: '模型生成', hint: '由生成引擎产出' }
    default:
      return { label: strategy || '未知', hint: '' }
  }
}

/** 原因码 → 中文。未收录的码原样展示，不吞掉 */
export function filterReasonLabel(code: string): string {
  const MAP: Record<string, string> = {
    HARD_CONSTRAINT_VIOLATION: '违反硬约束',
    HARD_CONSTRAINT_ESCALATED: '硬约束转软扣分',
    BOUNDARY_WARNING: '边界告警',
    PAINS_HIT: 'PAINS 结构警报',
    BRENK_HIT: 'BRENK 结构警报',
    ILLEGAL_RESIDUE: '非法残基/结构无法解析',
    QUOTA_CUT: '超出多样性配额',
    MERGED_DUPLICATE: '与已有候选重复合并'
  }
  return MAP[code] || code
}

/** 解析 `filterReason` 字段（后端给的是 JSON 字符串） */
export function parseFilterReasons(raw?: string | null): FilterReasonItem[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null)
      .map((x) => ({
        code: String(x.code ?? ''),
        message: String(x.message ?? ''),
        severity: String(x.severity ?? 'INFO')
      }))
  } catch {
    // 解析失败不静默丢弃：把原文作为一条 INFO 展示，好过让用户以为「没有原因」
    return [{ code: 'UNPARSED', message: raw, severity: 'INFO' }]
  }
}

/** 漏斗四段的中文名与顺序 */
export const FUNNEL_STAGES = [
  { key: 'generated', label: '生成', hint: 'S4 分片产出的原始候选' },
  { key: 'afterDedup', label: '去重后', hint: 'S5 序列哈希/结构聚类合并' },
  { key: 'afterPreFilter', label: '粗筛后', hint: 'S6 硬约束 + 安全性目录' },
  { key: 'afterQuota', label: '配额后', hint: 'S7 多样性轮转分配' }
] as const
