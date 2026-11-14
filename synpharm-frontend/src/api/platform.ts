/**
 * 平台域 / 系统级接口（能力矩阵、算法引擎健康）。
 *
 * 这些端点挂在 synpharm-platform 下（/api/platform/**），与 design 无关，
 * 因此不放在 design.ts 里，避免 mock 开关误把能力矩阵也 mock 掉。
 */
import { request } from '@/utils/request'

/** 模型能力：dti / ppi / ddi → 是否 ready；未探测到为 null */
export interface ModelCapabilities {
  dti?: boolean | null
  ppi?: boolean | null
  ddi?: boolean | null
}

/** 引擎能力：rdkit / crem / p2rank / vina / admet …；未探测到为 null */
export interface EngineCapabilities {
  [key: string]: boolean | null
}

/** 能力矩阵 /api/platform/capabilities */
export interface CapabilitiesResponse {
  /** 本注册表最后一次刷新成功的时间；从未探测过为 null */
  probedAt?: string | null
  tools?: Array<{
    toolCode: string
    available: boolean
    probeMsg?: string | null
    realness?: string | null
  }>
  engines?: EngineCapabilities | null
  models?: ModelCapabilities | null
}

/** 算法引擎健康 /api/platform/algorithm-health */
export interface AlgorithmHealthResponse {
  status: 'UP' | 'DOWN' | string
  fastapiStatus?: string | null
  latencyMs?: number | null
  message?: string | null
  checkedAt?: string | null
}

export const platformApi = {
  /** 当前能力矩阵（含 DTI/PPI/DDI 模型就绪状态） */
  fetchCapabilities: () => request.get<CapabilitiesResponse>('/api/platform/capabilities'),

  /** 算法引擎（FastAPI）健康探测 */
  fetchAlgorithmHealth: () =>
    request.get<AlgorithmHealthResponse>('/api/platform/algorithm-health')
}
