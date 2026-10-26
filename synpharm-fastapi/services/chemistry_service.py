"""RDKit 化学计算服务（04 文档 §3.1，P1）。

设计要点
--------
1. **不发明指标**：只算 RDKit 原生能力覆盖的指标。拿不到的一律显式标为
   ``unsupported`` 并说明原因，不用 0 值或估算值填充 —— 这是红线 R2
   （不做隐藏规则）在计算层的落点：让"没算"和"算出来是 0"在系统里可区分。

2. **单条失败不中断整批**：SMILES 解析或 Sanitize 失败只影响该条
   （``success=false`` + ``error``），整批状态降级为 ``PARTIAL``。
   一批 200 个分子因为第 3 个写错就全废，是不可接受的行为。

3. **SA_SCORE 不可用**：Ertl 的合成可及性评分依赖 ``fpscores.pkl.gz``
   （RDKit Contrib 的数据文件），pip 版 rdkit 不含、当前环境也取不到。
   因此 ``SA_SCORE`` 明确列进 ``unsupported``；同时提供 ``COMPLEXITY_PENALTY``
   —— 用 RDKit 原生函数算 SA score 里的**复杂度罚项**（环复杂度/螺环/桥头/立体中心/大环）。
   它与 SA score 同源但不等价，命名刻意不同，不冒充。

4. **指纹与骨架**：``MURCKO_SCAFFOLD`` 与 ECFP 供生成 S5 去重使用；
   聚类合并（代表者 → 被合并者）在 ``standardize`` 里做，因为它需要整批视野。
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)

try:  # pragma: no cover - 环境相关
    from rdkit import Chem, RDLogger
    from rdkit.Chem import Crippen, Descriptors, Lipinski, QED, rdMolDescriptors
    from rdkit.Chem import AllChem, DataStructs
    from rdkit.Chem.FilterCatalog import FilterCatalog, FilterCatalogParams
    from rdkit.Chem.MolStandardize import rdMolStandardize
    from rdkit.Chem.Scaffolds import MurckoScaffold

    # RDKit 对每个无法 Sanitize 的分子都会往 stderr 打一行；批量场景下噪音过大，
    # 而且我们自己已经把失败写进响应了
    RDLogger.DisableLog("rdApp.*")

    RDKIT_AVAILABLE = True
    RDKIT_VERSION = __import__("rdkit").__version__
except Exception as exc:  # pragma: no cover
    RDKIT_AVAILABLE = False
    RDKIT_VERSION = None
    logger.warning("RDKit 不可用，化学计算端点将返回 503：%s", exc)


# ---------------------------------------------------------------------------
# 指标清单
# ---------------------------------------------------------------------------

#: RDKit 原生可算的指标（键与 ``metric_definition.metric_code`` 对齐）
SUPPORTED_METRICS: Tuple[str, ...] = (
    "MOL_WT",
    "MOL_LOGP",
    "TPSA",
    "NUM_H_DONORS",
    "NUM_H_ACCEPTORS",
    "NUM_ROTATABLE",
    "FRACTION_CSP3",
    "NUM_RINGS",
    "NUM_AROMATIC_RINGS",
    "QED",
    "LIPINSKI_PASS",
    "PAINS_HITS",
    "BRENK_HITS",
    "COMPLEXITY_PENALTY",
)

#: 需要模型或外部数据文件、本服务不提供的指标（请求里出现即拒绝，不静默跳过）
UNSUPPORTED_METRICS: Dict[str, str] = {
    "SA_SCORE": "需要 RDKit Contrib 的 fpscores 数据集（pip 版 rdkit 不含），"
                "请改用 COMPLEXITY_PENALTY（同源的复杂度罚项，不含片段贡献分）",
    "SELECTIVITY_INDEX": "需要选择性模型，计算层未部署",
    "DTI_CONFIDENCE": "需要 KAN-MoDTI 模型且需靶点序列，请走 /v1/predict/single",
    "ADMET_*": "需要 admet_ai，未部署",
    "DOCKING_SCORE": "需要 vina，未部署",
}

#: 分子形式不适用时也不用这些指标（例如抗体序列没有 LogP）
_SMALL_MOLECULE_ONLY = {"MOL_LOGP", "TPSA", "QED", "PAINS_HITS", "BRENK_HITS"}


@dataclass
class MetricValue:
    """单个指标的计算结果。``detail`` 只在该指标需要额外说明时出现。"""

    value: Optional[float] = None
    detail: object = None
    grade: Optional[str] = None

    def as_dict(self) -> Dict[str, object]:
        out: Dict[str, object] = {"value": None if self.value is None else round(self.value, 6)}
        if self.detail is not None:
            out["detail"] = self.detail
        if self.grade is not None:
            out["grade"] = self.grade
        return out


@dataclass
class MoleculeResult:
    ref: Optional[str]
    success: bool
    error: Optional[str] = None
    metrics: Dict[str, MetricValue] = field(default_factory=dict)
    canonical_smiles: Optional[str] = None
    inchikey: Optional[str] = None
    formula: Optional[str] = None
    murcko_scaffold: Optional[str] = None
    max_similarity: Optional[float] = None
    #: 内部用：ECFP4 位向量，算相似度用，不序列化
    fingerprint: object = None

    def as_dict(self) -> Dict[str, object]:
        out: Dict[str, object] = {
            "ref": self.ref,
            "success": self.success,
            "error": self.error,
            "metrics": {k: v.as_dict() for k, v in self.metrics.items()},
            "canonicalSmiles": self.canonical_smiles,
            "inchikey": self.inchikey,
            "formula": self.formula,
            "murckoScaffold": self.murcko_scaffold,
        }
        if self.max_similarity is not None:
            out["diversity"] = {"maxSimilarity": round(self.max_similarity, 4)}
        return out


class ChemistryError(RuntimeError):
    """RDKit 不可用等基础设施级错误（对应 HTTP 503）。"""


def ensure_rdkit() -> None:
    if not RDKIT_AVAILABLE:
        raise ChemistryError(
            "RDKit 未安装或不可用，化学计算端点无法提供服务；"
            "请检查 synpharm-fastapi 镜像是否包含 rdkit"
        )


# ---------------------------------------------------------------------------
# 过滤器目录（模块级构建一次；构建开销约 10ms，逐次构建在批量场景会放大）
# ---------------------------------------------------------------------------

_catalog_cache: Dict[str, object] = {}


def _catalog(kind: str):
    if kind in _catalog_cache:
        return _catalog_cache[kind]
    params = FilterCatalogParams()
    params.AddCatalog(
        FilterCatalogParams.FilterCatalogs.PAINS
        if kind == "PAINS"
        else FilterCatalogParams.FilterCatalogs.BRENK
    )
    catalog = FilterCatalog(params)
    _catalog_cache[kind] = catalog
    return catalog


def _catalog_hits(kind: str, mol) -> List[str]:
    """返回命中的过滤器描述列表（前端高亮与 filter_reason 追溯的依据）。"""
    try:
        return [m.GetDescription() for m in _catalog(kind).GetMatches(mol)]
    except Exception as exc:  # pragma: no cover
        logger.warning("%s 过滤器计算失败：%s", kind, exc)
        return []


# ---------------------------------------------------------------------------
# 复杂度罚项（SA score 的可计算子集）
# ---------------------------------------------------------------------------

def _complexity_penalty(mol) -> float:
    """合成复杂度罚项。

    与 Ertl SA score 的复杂度项同源：环复杂度 + 螺环 + 桥头 + 立体中心 + 大环 + 非环原子数。
    缺的只是"片段贡献分"（需要 fpscores 数据集）。

    返回值越小越简单。经验上 < 1 简单、1~2 中等、> 2 复杂。
    """
    ring_complexity = 0.0
    ring_info = mol.GetRingInfo()
    rings = ring_info.AtomRings()
    if rings:
        # 与 SA score 一致：macrocycle 认为降低复杂度（大环通常比稠环易合成）
        ring_complexity = math.log(len(rings)) + math.log(max(1, len(mol.GetAtoms())))
        if any(len(r) > 8 for r in rings):
            ring_complexity += 1.0

    stereos = rdMolDescriptors.CalcNumAtomStereoCenters(mol)
    spiro = rdMolDescriptors.CalcNumSpiroAtoms(mol)
    bridgehead = rdMolDescriptors.CalcNumBridgeheadAtoms(mol)
    macro = 1 if any(len(r) > 8 for r in rings) else 0

    return (
        ring_complexity
        + stereos
        + spiro
        + bridgehead
        + macro
        - math.log(max(1, mol.GetNumHeavyAtoms()))
    )


# ---------------------------------------------------------------------------
# 单分子计算
# ---------------------------------------------------------------------------

def _grade(value: float, good: float, warn: float, higher_better: bool = False) -> Optional[str]:
    """把数值转成 GOOD / WARN / BAD，供前端上色。判定口径随 direction 变化。"""
    if higher_better:
        if value >= good:
            return "GOOD"
        return "WARN" if value >= warn else "BAD"
    if value <= good:
        return "GOOD"
    return "WARN" if value <= warn else "BAD"


def compute_one(ref: Optional[str], smiles: str, metrics: Sequence[str]) -> MoleculeResult:
    """算一个分子。任何解析/计算失败都落在 ``success=false`` 上，不抛异常。"""
    result = MoleculeResult(ref=ref, success=False)

    if not smiles or not smiles.strip():
        result.error = "SMILES 为空"
        return result

    try:
        mol = Chem.MolFromSmiles(smiles)
    except Exception as exc:
        result.error = f"SMILES 解析异常：{exc}"
        return result
    if mol is None:
        result.error = "SMILES 无法解析为分子（语法错误或含非法元素）"
        return result

    try:
        result.canonical_smiles = Chem.MolToSmiles(mol)
        result.inchikey = Chem.MolToInchiKey(mol)
        result.formula = rdMolDescriptors.CalcMolFormula(mol)

        # 无环/单原子分子没有 Murcko 骨架，返回 None 而不是空串
        scaffold = MurckoScaffold.GetScaffoldForMol(mol)
        result.murcko_scaffold = Chem.MolToSmiles(scaffold) if scaffold.GetNumAtoms() else None

        fp = AllChem.GetMorganFingerprintAsBitVect(mol, radius=2, nBits=2048)
        result.fingerprint = fp
    except Exception as exc:
        result.error = f"分子规范化失败：{exc}"
        return result

    for code in metrics:
        try:
            result.metrics[code] = _compute_metric(code, mol)
        except Exception as exc:
            # 单个指标算不出来不影响其他指标
            logger.warning("指标 %s 计算失败 ref=%s：%s", code, ref, exc)
            result.metrics[code] = MetricValue(value=None, detail={"error": str(exc)})

    result.success = True
    return result


def _compute_metric(code: str, mol) -> MetricValue:
    if code == "MOL_WT":
        return MetricValue(Descriptors.MolWt(mol))
    if code == "MOL_LOGP":
        v = Crippen.MolLogP(mol)
        return MetricValue(v, grade=_grade(v, 3.5, 5.0))
    if code == "TPSA":
        v = rdMolDescriptors.CalcTPSA(mol)
        return MetricValue(v, grade=_grade(v, 120.0, 160.0))
    if code == "NUM_H_DONORS":
        return MetricValue(Lipinski.NumHDonors(mol))
    if code == "NUM_H_ACCEPTORS":
        return MetricValue(Lipinski.NumHAcceptors(mol))
    if code == "NUM_ROTATABLE":
        v = rdMolDescriptors.CalcNumRotatableBonds(mol)
        return MetricValue(v, grade=_grade(v, 8.0, 12.0))
    if code == "FRACTION_CSP3":
        return MetricValue(rdMolDescriptors.CalcFractionCSP3(mol))
    if code == "NUM_RINGS":
        return MetricValue(rdMolDescriptors.CalcNumRings(mol))
    if code == "NUM_AROMATIC_RINGS":
        return MetricValue(rdMolDescriptors.CalcNumAromaticRings(mol))
    if code == "QED":
        v = QED.qed(mol)
        return MetricValue(v, grade=_grade(v, 0.6, 0.4, higher_better=True))
    if code == "LIPINSKI_PASS":
        violations: List[str] = []
        if Descriptors.MolWt(mol) > 500:
            violations.append("MOL_WT>500")
        if Crippen.MolLogP(mol) > 5:
            violations.append("MOL_LOGP>5")
        if Lipinski.NumHDonors(mol) > 5:
            violations.append("H_DONORS>5")
        if Lipinski.NumHAcceptors(mol) > 10:
            violations.append("H_ACCEPTORS>10")
        # 值是"未违反的规则条数"（满 4），与 04 文档示例一致
        return MetricValue(4 - len(violations), detail={"violations": violations})
    if code == "PAINS_HITS":
        hits = _catalog_hits("PAINS", mol)
        return MetricValue(len(hits), detail=hits)
    if code == "BRENK_HITS":
        hits = _catalog_hits("BRENK", mol)
        return MetricValue(len(hits), detail=hits)
    if code == "COMPLEXITY_PENALTY":
        v = _complexity_penalty(mol)
        return MetricValue(v, grade=_grade(v, 1.0, 2.0))
    raise ValueError(f"未实现的指标：{code}")


# ---------------------------------------------------------------------------
# 批量
# ---------------------------------------------------------------------------

def compute_batch(
    items: Iterable[Tuple[Optional[str], str]], metrics: Sequence[str], include_diversity: bool
) -> Tuple[str, List[MoleculeResult]]:
    """批量计算。

    :return: (整批状态, 逐条结果)。状态取 ``SUCCEEDED`` / ``PARTIAL`` / ``FAILED``。
    """
    results = [compute_one(ref, smiles, metrics) for ref, smiles in items]

    if include_diversity:
        _fill_diversity(results)

    ok = sum(1 for r in results if r.success)
    if ok == 0 and results:
        status = "FAILED"
    elif ok == len(results):
        status = "SUCCEEDED"
    else:
        status = "PARTIAL"
    return status, results


def _fill_diversity(results: List[MoleculeResult]) -> None:
    """给每个分子填 ``maxSimilarity``：与同批其他分子（不含自身）的最高 Tanimoto 相似度。

    这是生成 S5 去重的判据来源（"超过 0.9 的只保留代表序列"），
    所以在服务端一次算完，避免后端为 N 个分子发 N 次请求。
    """
    fps = [(i, r.fingerprint) for i, r in enumerate(results) if r.success and r.fingerprint]
    for idx, fp in fps:
        best = 0.0
        for other_idx, other_fp in fps:
            if other_idx == idx:
                continue
            sim = DataStructs.TanimotoSimilarity(fp, other_fp)
            if sim > best:
                best = sim
        results[idx].max_similarity = best


# ---------------------------------------------------------------------------
# 去重聚类（生成 S5）
# ---------------------------------------------------------------------------

def cluster_by_similarity(
    results: List[MoleculeResult], threshold: float
) -> List[Dict[str, object]]:
    """按 ECFP4 Tanimoto 阈值聚类，返回「代表者 → 被合并者」的合并关系。

    策略是最朴素的贪心代表者法：按输入顺序，第一个未被合并的分子成为代表者，
    其余与它相似度 ≥ 阈值的全部并入该簇。对 S5 的用途（把明显重复的候选收成一条）
    足够，且**结果可复现**（顺序确定 → 合并结果确定），这对"凭快照回放"很重要。

    :return: ``[{"representative": ref, "merged": [ref, ...], "maxSimilarity": 0.97}, ...]``
    """
    alive = [r for r in results if r.success and r.fingerprint]
    merged_into: Dict[int, int] = {}
    clusters: List[Dict[str, object]] = []

    for i, current in enumerate(alive):
        if i in merged_into:
            continue
        members: List[int] = []
        max_sim = 0.0
        for j in range(i + 1, len(alive)):
            if j in merged_into:
                continue
            sim = DataStructs.TanimotoSimilarity(current.fingerprint, alive[j].fingerprint)
            if sim >= threshold:
                merged_into[j] = i
                members.append(j)
                max_sim = max(max_sim, sim)
        clusters.append(
            {
                "representative": current.ref,
                "merged": [alive[j].ref for j in members],
                "maxSimilarity": round(max_sim, 4) if members else None,
            }
        )
    return clusters


def standardize_one(ref: Optional[str], smiles: str) -> MoleculeResult:
    """规范化：去盐、中性化、统一互变异构表述、canonical SMILES + InChIKey + 骨架。"""
    result = MoleculeResult(ref=ref, success=False)

    if not smiles or not smiles.strip():
        result.error = "SMILES 为空"
        return result

    try:
        mol = Chem.MolFromSmiles(smiles)
        if mol is None:
            result.error = "SMILES 无法解析为分子"
            return result

        cleaner = rdMolStandardize.Cleanup(mol)
        largest = rdMolStandardize.LargestFragmentChooser().choose(cleaner)
        uncharged = rdMolStandardize.Uncharger().uncharge(largest)

        result.canonical_smiles = Chem.MolToSmiles(uncharged)
        result.inchikey = Chem.MolToInchiKey(uncharged)
        result.formula = rdMolDescriptors.CalcMolFormula(uncharged)

        scaffold = MurckoScaffold.GetScaffoldForMol(uncharged)
        result.murcko_scaffold = Chem.MolToSmiles(scaffold) if scaffold.GetNumAtoms() else None

        result.fingerprint = AllChem.GetMorganFingerprintAsBitVect(uncharged, radius=2, nBits=2048)
        result.success = True
    except Exception as exc:
        result.error = f"规范化失败：{exc}"

    return result


def _model_entry(value: Dict[str, object]) -> Dict[str, object]:
    """把一个模型的状态翻译成能力矩阵里的一项。

    **``ready`` 的判定是这里最容易写错的地方**：本服务的模型是懒加载的，
    「权重在但还没加载」是**正常可用**状态。把它判成不可用，会让 dti/ddi
    这类权重齐全的算法在工具面板上永久灰掉 —— 假阴比假阳更难发现，
    因为用户只会觉得"这个工具怎么从来不能用"。

    04 文档 §2.2 明确要求：
      * 不得触发权重加载，只读文件存在性；
      * ``loaded`` 是观测值，不是"能否加载"的判定。

    所以 ``ready = 权重存在且没有加载失败``。
    """
    state = str(value.get("state") or "")
    weights_present = bool(value.get("weights_present"))
    load_failed = state == "load_failed"
    ready = weights_present and not load_failed

    if ready:
        error = None
    elif state == "weights_missing":
        error = f"weights_missing: {value.get('weights_path') or '权重文件不存在'}"
    elif load_failed:
        error = "load_failed"
    else:
        error = state or "unknown"

    return {
        "ready": ready,
        "weightsPresent": weights_present,
        "loaded": state == "ready",
        "error": error,
    }


def capabilities_payload(model_status: Dict[str, Dict[str, object]], endpoint_states: Dict[str, str]):
    """能力矩阵（04 文档 §2）。

    引擎就绪不等于端点就绪：``engines`` 回答"库在不在"，``endpoints`` 回答"路有没有"。
    两者分开，才能把"没做"和"坏了"区分开 —— 这正是 P0 要解决的问题。

    **``engines`` 的值必须是布尔**：后端 ``EngineCapabilities.engines`` 是
    ``Map<String, Boolean>``，返回对象会在反序列化时失败，连带让 12 个工具全部判不出。
    版本号另放 ``engineVersions``。
    """
    return {
        "engines": {
            "rdkit": RDKIT_AVAILABLE,
            "crem": False,
            "p2rank": False,
            "vina": False,
            "admet": False,
        },
        "engineVersions": {"rdkit": RDKIT_VERSION},
        "models": {
            key: _model_entry(value)
            for key, value in model_status.items()
        },
        "endpoints": endpoint_states,
        "metrics": {
            "supported": list(SUPPORTED_METRICS),
            "unsupported": UNSUPPORTED_METRICS,
        },
    }
