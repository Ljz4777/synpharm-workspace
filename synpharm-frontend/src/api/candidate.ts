/**
 * 手工候选 · 接口门面
 * ============================================================================
 * 二维画布上画出来的结构，用这个接口变成真正可以评估、可以决策的候选。
 *
 * 与生成漏斗的区别：生成是「引擎产分子」，这里是「人给分子」。
 * 两者落到同一个 `candidate_molecule` 表，区别只记在 `origin_type`（IMPORTED / GENERATED）——
 * 因为后续每一步（评估、编辑、决策）都只关心「这是哪个分子」，不关心它是怎么来的。
 */
import { request } from '@/utils/request'

export interface ManualCandidateResponse {
  candidateId: number
  projectId: number
  roundId?: number | null
  /** 用户输入的 SMILES（原样保留） */
  inputSmiles?: string
  /** 引擎归一化后的 SMILES */
  smiles?: string
  inchikey?: string
  formula?: string
  mw?: number | null
  logp?: number | null
  qed?: number | null
  /** 引擎实际返回的指标条数（其余这次没算出来，不补 0） */
  metricCount?: number
  originType?: string
  /** true = 该分子此前已存在，candidateId 指向已有那条 */
  existed?: boolean
}

export const manualCandidateApi = {
  /**
   * 用 SMILES 建候选并挂到项目当前轮次。
   *
   * 结构和指标全部由后端实算 —— 前端传的分子量/InChIKey 一律不采信，
   * 否则「这个结构到底是不是你以为的那个分子」就失去保证。
   */
  create(projectId: number, smiles: string, moleculeName?: string) {
    return request.post<ManualCandidateResponse>(`/api/design/projects/${projectId}/candidates`, {
      smiles,
      moleculeName: moleculeName || undefined
    })
  },

  /**
   * 把画布上的结构写成某个候选的**新版本**（版本树里挂在它下面）。
   *
   * 与 `create` 的区别：`create` 产出独立根（一棵新树），这里产出同一分子的下一版 ——
   * 版本树、缺口诊断、影响面守卫都靠这条父子关系讲清「改了什么、代价是什么」。
   * `describeText` 必填：没有说明的节点，事后只能看 SMILES 猜意图。
   */
  createVersion(candidateId: number, smiles: string, describeText: string, targetRegion?: string) {
    return request.post<CandidateVersionResponse>(`/api/design/candidates/${candidateId}/versions`, {
      smiles,
      describeText,
      targetRegion: targetRegion || undefined
    })
  }
}

export interface CandidateVersionResponse {
  candidateId: number
  parentCandidateId: number
  versionNo: number
  editRound: number
  smiles?: string
  inchikey?: string
  formula?: string
  mw?: number | null
  logp?: number | null
  qed?: number | null
  metricCount?: number
  originType?: string
  /** 恒为 true：新版本还没有分数，必须重新冻结才能进决策 */
  needsReEval?: boolean
  editLogId?: number
}
