"""评估能力端点（04 文档 §2 P0 + §3.1 P1）。

`/capabilities` 是「能力真实性」机制的数据源：后端 `ToolRegistryService` 每 10 分钟
拉一次，据此写 `design_tool.available`。没有它，12 个工具只能靠静态种子，而静态种子
必然与真实状态脱节 —— 用户点下去才发现没实现。
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone, timedelta
from typing import List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, field_validator

from services import chemistry_service as chem
from services.algorithm_adapters import get_model_status

logger = logging.getLogger(__name__)
router = APIRouter()

_CST = timezone(timedelta(hours=8))

#: 端点状态表：与路由表一并维护。
#: READY 表示"已实现且依赖就绪"，NOT_IMPLEMENTED 表示"路还没修"——
#: 两者都不代表"调用一定成功"（参数错误等属运行时问题）。
_ENDPOINTS = {
    "/v1/predict/single": "READY",
    "/v1/predict/batch": "READY",
    "/v1/ddi/drugs": "READY",
    "/v1/evaluate/capabilities": "READY",
    "/v1/evaluate/properties": "READY",
    "/v1/chemistry/standardize": "READY",
    "/v1/molecules/edit": "NOT_IMPLEMENTED",
    "/v1/generate": "NOT_IMPLEMENTED",
    "/v1/pockets/detect": "NOT_IMPLEMENTED",
    "/v1/structures/fetch": "NOT_IMPLEMENTED",
    "/v1/targets/resolve": "NOT_IMPLEMENTED",
    "/v1/evaluate/docking": "NOT_IMPLEMENTED",
    "/v1/evaluate/admet": "NOT_IMPLEMENTED",
    "/v1/feedback/calibrate": "NOT_IMPLEMENTED",
    "/v1/retrieval/pubmed": "NOT_IMPLEMENTED",
}


@router.get("/capabilities")
def capabilities():
    """能力矩阵。

    * 不触发权重加载：只读文件存在性与已加载状态（复用 ``get_model_status``）
    * ``engines.rdkit.available`` 是"库在不在"；``endpoints`` 是"路有没有"
    * P95 目标 < 50ms（纯本地检查 + 常量表）
    """
    payload = chem.capabilities_payload(get_model_status(), _ENDPOINTS)
    return {"probedAt": datetime.now(_CST).isoformat(), **payload}


# ---------------------------------------------------------------------------
# /properties
# ---------------------------------------------------------------------------


class MoleculeIn(BaseModel):
    ref: Optional[str] = Field(default=None, description="调用方自定义标识，原样回传")
    smiles: str = Field(description="分子 SMILES")


class PropertiesRequest(BaseModel):
    molecules: List[MoleculeIn] = Field(min_length=1)
    metrics: Optional[List[str]] = Field(
        default=None, description="留空表示算全部 RDKit 原生可算指标"
    )
    includeDiversity: bool = Field(
        default=False, description="是否一并计算同批最大 Tanimoto 相似度（S5 去重用）"
    )

    @field_validator("molecules")
    @classmethod
    def _limit(cls, v: List[MoleculeIn]) -> List[MoleculeIn]:
        # 批量上限放在入口：500 个分子 × 14 指标约 1.5s，再多就会撞后端 30s 超时
        if len(v) > 500:
            raise ValueError("单次最多 500 个分子；请分批调用")
        return v


@router.post("/properties")
def properties(req: PropertiesRequest):
    """批量理化/成药性计算（04 文档 §3.1）。

    三条硬约束：
      * ``success=false`` 时 ``metrics`` 可为空 —— 后端据此写 ``evaluation_result.success=0``，
        绝不用 0 值填充（红线 R2）；
      * ``PAINS_HITS`` / ``BRENK_HITS`` 的 ``detail`` 给出命中列表；
      * 需模型的指标（SA_SCORE / SELECTIVITY_INDEX / DTI_CONFIDENCE 等）**整批拒绝**，
        不静默跳过 —— 否则调用方会以为"这些指标算过了"。
    """
    try:
        chem.ensure_rdkit()
    except chem.ChemistryError as exc:
        raise HTTPException(status_code=503, detail=str(exc))

    requested = req.metrics or list(chem.SUPPORTED_METRICS)

    rejected: List[dict] = []
    for code in requested:
        if code in chem.UNSUPPORTED_METRICS:
            rejected.append({"metric": code, "reason": chem.UNSUPPORTED_METRICS[code]})
        elif code not in chem.SUPPORTED_METRICS:
            rejected.append({"metric": code, "reason": "未识别或未实现的指标码"})
    if rejected:
        raise HTTPException(
            status_code=400,
            detail={
                "error": "UNSUPPORTED_METRIC",
                "message": "请求里包含本服务不提供的指标，已整批拒绝（避免用假数据填充）",
                "metrics": rejected,
                "supported": list(chem.SUPPORTED_METRICS),
            },
        )

    status, results = chem.compute_batch(
        [(m.ref, m.smiles) for m in req.molecules], requested, req.includeDiversity
    )

    return {
        "status": status,
        "results": [r.as_dict() for r in results],
        "engine": {"rdkit": chem.RDKIT_VERSION},
        "metrics": requested,
    }
