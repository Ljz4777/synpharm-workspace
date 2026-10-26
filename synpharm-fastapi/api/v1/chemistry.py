"""化学结构端点：规范化 + 去重聚类。

对应生成阶段的 S5（去重）。**只做确定性运算**，不调用任何模型。

为什么把"去重"放在计算层而不是后端循环调用：
  一次 500 个分子的两两 Tanimoto 约 125k 次比较（RDKit C 实现，~40ms），
  放在后端要么发 500 次 HTTP、要么在 Java 里重写指纹算法 —— 前者慢，后者必然与
  RDKit 结果有偏差（去重结果不一致会让"同一批候选"在两端条数不同）。
"""

from __future__ import annotations

import logging
from typing import List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, field_validator

from services import chemistry_service as chem

logger = logging.getLogger(__name__)
router = APIRouter()


class StandardizeItem(BaseModel):
    ref: Optional[str] = None
    smiles: str


class StandardizeRequest(BaseModel):
    molecules: List[StandardizeItem] = Field(min_length=1)
    #: 给出时一并做相似度聚类（生成 S5）；不给则只规范化
    dedupThreshold: Optional[float] = Field(
        default=None, ge=0.5, le=1.0, description="ECFP4 Tanimoto 阈值，0.9 表示 90% 相似即合并"
    )

    @field_validator("molecules")
    @classmethod
    def _limit(cls, v: List[StandardizeItem]) -> List[StandardizeItem]:
        if len(v) > 2000:
            raise ValueError("单次最多 2000 个分子")
        return v


@router.post("/standardize")
def standardize(req: StandardizeRequest):
    """规范化分子并（可选）聚类去重。

    规范化做的事：去盐（保留最大片段）、中性化、统一 canonical SMILES、
    给出 InChIKey / 分子式 / Murcko 骨架。

    为什么必须先规范化再比对：``CC(=O)Oc1ccccc1C(=O)O`` 与
    ``OC(=O)c1ccccc1OC(C)=O`` 是同一个分子，直接字符串比对会把它们当成两个候选。
    """
    try:
        chem.ensure_rdkit()
    except chem.ChemistryError as exc:
        raise HTTPException(status_code=503, detail=str(exc))

    results = [chem.standardize_one(item.ref, item.smiles) for item in req.molecules]

    ok = sum(1 for r in results if r.success)
    status = "FAILED" if ok == 0 else ("SUCCEEDED" if ok == len(results) else "PARTIAL")

    payload = {
        "status": status,
        "results": [r.as_dict() for r in results],
        "engine": {"rdkit": chem.RDKIT_VERSION},
    }

    if req.dedupThreshold is not None:
        clusters = chem.cluster_by_similarity(results, req.dedupThreshold)
        payload["dedup"] = {
            "threshold": req.dedupThreshold,
            "clusterCount": len(clusters),
            "mergedCount": sum(len(c["merged"]) for c in clusters),
            "clusters": clusters,
        }

    return payload
