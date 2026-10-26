/**
 * 靶点库 · 接口门面
 * ============================================================================
 * 只有真实实现，没有 mock。原因：靶点与结构是需求基线块1/块2 的**唯一来源**，
 * 用假数据演示会让人以为「结构缺失也能立项」，而那正是基线要拦的事。
 *
 * 端点：`TargetController`
 *   GET /api/design/targets?keyword&targetType&diseaseArea&status&page&pageSize
 *   GET /api/design/targets/{id}                含结构与每个结构的口袋
 *   GET /api/design/structures/{id}/pockets
 */
import { request } from '@/utils/request'

/** 结合口袋（对接用）。坐标与残基是真实数据，不是占位 */
export interface BindingPocket {
  id: number
  structureId: number
  pocketNo: number
  /** P2RANK / FPOCKET … */
  method?: string
  score?: number | null
  druggability?: number | null
  volume?: number | null
  centerX?: number | null
  centerY?: number | null
  centerZ?: number | null
  residues?: Array<Record<string, unknown>>
  isSelected?: boolean
  note?: string
}

/** 靶点的一条结构（晶体或预测） */
export interface TargetStructure {
  id: number
  targetId: number
  pdbId?: string | null
  chainId?: string | null
  /** PDB / ALPHAFOLD … */
  source?: string
  sourceKey?: string
  /** X_RAY / PREDICTED … */
  experimentMethod?: string
  resolution?: number | null
  ligandCount?: number | null
  pocketCount?: number | null
  isPreferred?: boolean
  fileAssetId?: number | null
  pockets?: BindingPocket[]
}

/** 靶点详情。列表接口也会回 structures（含 pockets） */
export interface TargetDetail {
  id: number
  uniprotId?: string | null
  name: string
  chineseName?: string | null
  geneName?: string | null
  organism?: string | null
  targetType?: string | null
  family?: string | null
  diseaseArea?: string | null
  description?: string | null
  pathway?: string | null
  relatedDiseases?: string | null
  knownDrugs?: string | null
  sequence?: string | null
  sequenceLength?: number | null
  pdbIds?: string | null
  status?: string | null
  structures?: TargetStructure[]
}

export interface TargetQuery {
  keyword?: string
  targetType?: string
  diseaseArea?: string
  status?: string
  page?: number
  pageSize?: number
}

export const targetApi = {
  list: (query: TargetQuery = {}) =>
    request.get<{ list: TargetDetail[]; total: number; page: number; pageSize: number; hasNext: boolean }>(
      '/api/design/targets',
      { params: { page: 1, pageSize: 50, ...query } }
    ),

  detail: (targetId: number) => request.get<TargetDetail>(`/api/design/targets/${targetId}`),

  pockets: (structureId: number) =>
    request.get<BindingPocket[]>(`/api/design/structures/${structureId}/pockets`)
}

/** 结构来源 */
export const STRUCTURE_SOURCE_LABEL: Record<string, string> = {
  PDB: '晶体结构（PDB）',
  ALPHAFOLD: 'AlphaFold 预测',
  HOMOLOGY: '同源建模'
}

/** 实验方法 */
export const EXPERIMENT_METHOD_LABEL: Record<string, string> = {
  X_RAY: 'X 射线衍射',
  NMR: '核磁共振',
  CRYO_EM: '冷冻电镜',
  PREDICTED: '预测模型',
  HOMOLOGY: '同源建模'
}

/** 口袋识别方法 */
export const POCKET_METHOD_LABEL: Record<string, string> = {
  P2RANK: 'P2Rank',
  FPOCKET: 'fpocket',
  MANUAL: '人工指定'
}
