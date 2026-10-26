/**
 * 决策（阶段 E）· 接口门面
 * ============================================================================
 * 与 `requirement.ts` / `generation.ts` / `evaluation.ts` 同一口径：**只有真实实现，没有 mock**。
 *
 * 决策是主流程的最后一环，它的产物是「拿哪几个分子去做湿实验」。
 * 这一环最不能用假数据演示 —— 名额、否决理由、回放指纹三者任一是编的，
 * 用户就会以为「被否决」是随机结果，而不是某条规则的必然结论。
 *
 * 端点与 `synpharm-deploy/docs/design/detailed/07-决策阶段设计方案.md` §8 一致。
 */
import { request } from '@/utils/request'

// ---------------------------------------------------------------- 枚举

/** 分桶：全通过 / 容忍误差内通过 / 不通过 */
export type DecisionBucket = 'FULL_PASS' | 'TOLERANCE' | 'FAIL'

/** 候选去向：选中 / 备选 / 淘汰 / 挂起 */
export type DecisionOutcome = 'SELECTED' | 'BACKUP' | 'REJECTED' | 'DEFERRED'

/** 决策记录状态。E2~E5 只产出 DRAFT，确认与回放在 E6~E8 */
export type DecisionStatus = 'DRAFT' | 'CONFIRMED' | 'DEFERRED' | 'CANCELLED'

/** 否决严重度：BLOCK 不可绕过（只能改数据重来），WARN 可接受但需留痕 */
export type VetoSeverity = 'BLOCK' | 'WARN'

// ---------------------------------------------------------------- 决策结论

/** 一条否决命中。`evidenceRef` 指回证据包里的具体字段 —— 只说「被否决」不说依据，用户无法判断该补数据还是该换分子 */
export interface VetoHit {
  ruleCode: string
  severity: VetoSeverity | string
  message: string
  evidenceRef?: string | null
}

/** 已接受的风险点（D7） */
export interface RiskAcceptance {
  id: number
  candidateId: number
  riskCode: string
  /** 接受当时的严重度快照 */
  severityAtAccept?: string | null
  reason: string
  acceptedBy?: number | null
  acceptedAt?: string | null
  /** null = 本决策内长期有效 */
  validUntil?: string | null
}

/** 回放的一条候选差异 */
export interface ReplayDiff {
  candidateId: number
  moleculeName?: string | null
  storedBucket?: string | null
  currentBucket?: string | null
  storedRankNo?: number | null
  currentRankNo?: number | null
  storedOutcome?: string | null
  currentOutcome?: string | null
  changes: string[]
}

export interface DecisionReplay {
  decisionId: number
  decisionNo: string
  storedInputHash: string
  recomputedInputHash: string
  storedRuleVersion: string
  currentRuleVersion: string
  same: boolean
  diffs: ReplayDiff[]
  addedCandidates: number[]
  removedCandidates: number[]
  warnings: string[]
}

/** 证据包里的风险点（D7 要照着它来点「接受」） */
export interface RiskFlag {
  riskCode: string
  severity?: string | null
  /** true = 必须实测验证，「接受风险」这条路走不通 */
  mustVerify: boolean
  source?: string | null
}

export interface DecisionCandidate {
  candidateId: number
  moleculeName?: string | null
  /** 生成阶段通常不起名，界面上要靠结构信息分辨「是哪个分子」 */
  smiles?: string | null
  mw?: number | null
  bucket: DecisionBucket | string
  /** FAIL 桶为 null —— 给淘汰对象编名次会把「没通过」伪装成「排在后面」 */
  rankNo?: number | null
  weightedScore?: number | null
  /** 加权分的分母只含这些指标；缺数据的指标不补零，因此必须同时看 covers */
  coversMetrics?: number | null
  missingMetrics: string[]
  outcome: DecisionOutcome | string
  /** FILTER_REASON 码（stage=DECISION），如 VETO_MUST_VERIFY_MISSING */
  rejectReason?: string | null
  manualOverride?: boolean | null
  overrideReason?: string | null
  /** 已接受的风险点；空表示没有接受过任何风险 */
  acceptances?: RiskAcceptance[]
  /** 证据包里的风险点清单 */
  risks?: RiskFlag[]
  vetoes: VetoHit[]
}

export interface Decision {
  id: number
  decisionNo: string
  projectId: number
  roundId?: number | null
  baselineSnapshotId?: number | null
  status: DecisionStatus | string
  /** 排序用的指标权重快照 —— 回放三件套之一 */
  weightsSnapshot: Record<string, unknown>
  /** 规则版本，形如 `veto-v1:<hash12>` */
  ruleVersion: string
  /** 全部证据包 hash 的排序聚合 —— 同一输入重算应当得到同一个值 */
  inputSnapshotHash: string
  selectedCount: number
  backupCount: number
  rejectedCount: number
  deferredCount: number
  /** 实际名额 = min(档位, 湿实验余额)，且至少 1 */
  capacity?: number | null
  decisionNote?: string | null
  versionNo: number
  revisionOf?: number | null
  createdAt?: string
  decidedAt?: string | null
  candidates: DecisionCandidate[]
  /** 规则层与名额层的告警（如 NO_VIABLE_CANDIDATE）；不是错误 */
  warnings: string[]
}

export interface DecisionCreateRequest {
  /** 指定轮次；为空表示取项目当前轮次 */
  roundId?: number | null
  decisionNote?: string
}

// ---------------------------------------------------------------- 否决规则

export interface DecisionVetoRule {
  id: number
  /** 0 表示全局默认规则 */
  projectId: number
  global: boolean
  ruleCode: string
  severity: VetoSeverity | string
  dimension?: string | null
  thresholdNum?: number | null
  paramsJson?: string | null
  /** 用户可读的判定说明（红线 R2：不允许存在用户看不见的规则） */
  humanReadable: string
  enabled: boolean
  sortNo?: number | null
  createdAt?: string
}

export interface DecisionVetoRuleRequest {
  ruleCode: string
  severity?: VetoSeverity | string
  dimension?: string
  thresholdNum?: number | null
  paramsJson?: string
  humanReadable: string
  enabled?: boolean
  sortNo?: number | null
}

// ---------------------------------------------------------------- 门面

export const decisionApi = {
  /** 新建一次决策：只读冻结快照，候选集由项目当前轮次已冻结的候选推导 */
  create(projectId: number, body: DecisionCreateRequest = {}) {
    return request.post<Decision>(`/api/design/projects/${projectId}/decisions`, body)
  },
  get(decisionId: number) {
    return request.get<Decision>(`/api/design/decisions/${decisionId}`)
  },
  listByProject(projectId: number) {
    return request.get<Decision[]>(`/api/design/projects/${projectId}/decisions`)
  },

  /** D6 人工调整。理由必填；被 BLOCK 否决或硬约束不通过的候选不能改为入选 */
  override(decisionId: number, candidateId: number, outcome: DecisionOutcome | string, overrideReason: string) {
    return request.patch<Decision>(
      `/api/design/decisions/${decisionId}/candidates/${candidateId}`,
      { outcome, overrideReason }
    )
  },

  /** D7 接受某个风险点（逐候选逐风险点）；「必须验证」项会被后端 409 拒掉 */
  acceptRisk(decisionId: number, candidateId: number, riskCode: string, reason: string) {
    return request.post<Decision>(
      `/api/design/decisions/${decisionId}/candidates/${candidateId}/accept-risk`,
      { riskCode, reason }
    )
  },

  /** D8 回放比对：用当前输入与规则重算，返回逐候选差异。无副作用 */
  replay(decisionId: number) {
    return request.post<DecisionReplay>(`/api/design/decisions/${decisionId}:replay`, {})
  },

  /** D8 确认冻结。前置：草稿 / 有候选入选 / 回放指纹一致 */
  confirm(decisionId: number, reason?: string) {
    return request.post<Decision>(`/api/design/decisions/${decisionId}:confirm`, reason ? { reason } : {})
  },

  /** 转暂缓（补数据后重开）。理由必填 */
  defer(decisionId: number, reason: string) {
    return request.post<Decision>(`/api/design/decisions/${decisionId}:defer`, { reason })
  },

  /** 取消（仅草稿）。理由必填 */
  cancel(decisionId: number, reason: string) {
    return request.post<Decision>(`/api/design/decisions/${decisionId}:cancel`, { reason })
  }
}

export const decisionVetoRuleApi = {
  /** 全局规则（projectId=0，只读）与本项目规则一起返回 */
  list(projectId: number) {
    return request.get<DecisionVetoRule[]>(`/api/design/projects/${projectId}/decision-veto-rules`)
  },
  create(projectId: number, body: DecisionVetoRuleRequest) {
    return request.post<DecisionVetoRule>(
      `/api/design/projects/${projectId}/decision-veto-rules`,
      body
    )
  },
  update(ruleId: number, body: DecisionVetoRuleRequest) {
    return request.put<DecisionVetoRule>(`/api/design/decision-veto-rules/${ruleId}`, body)
  },
  remove(ruleId: number) {
    return request.delete<void>(`/api/design/decision-veto-rules/${ruleId}`)
  }
}
