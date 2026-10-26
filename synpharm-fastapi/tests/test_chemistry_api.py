"""化学计算端点测试（04 文档 §357 要求新端点必须带测试）。

跑法（容器内，零额外依赖）::

    docker exec synpharm-dev-fastapi sh -c "cd /app && python -m unittest discover -s tests -v"

为什么直连真实服务而不是 ``fastapi.testclient.TestClient``：镜像里
``httpx 0.28.1`` 与 ``starlette 0.27.0`` 不兼容（httpx 0.28 去掉了 ``Client(app=...)``），
``TestClient(app)`` 会直接抛 TypeError。升级 starlette 有回归风险，而直连真实端口
反而更接近生产 —— 它同时验证了「服务起来了」和「端点算对了」两件事。

为什么用 unittest 而不是 pytest：镜像里没装 pytest。unittest 是标准库，
任何 Python 3 环境都能跑。

测试意图
--------
* **数值对得上**：阿司匹林 MW 180.16 / 咖啡因 InChIKey —— 有公开权威值可比，
  算错了立刻暴露（这比"返回 200"有意义得多）。
* **失败不中断整批**：一批里混一个非法 SMILES，其余必须正常出结果，整批降级 PARTIAL。
* **不冒充**：请求 SA_SCORE 必须 400，而不是返回 0 或估算值。
* **去重语义**：同一分子的两种 SMILES 写法必须得到同一个 InChIKey。
"""

import json
import os
import unittest

import httpx

BASE = os.getenv("FASTAPI_BASE", "http://localhost:8000")
TIMEOUT = 30.0

#: 服务端用 ``API_KEYS``（逗号分隔或 JSON 数组），与 Java 侧的 ``FASTAPI_API_KEY`` 是两个名字。
#: 测试直接复用它：认证是端点的既有前提，绕过去测等于少测一层。
_RAW_KEYS = (os.getenv("API_KEYS") or "").strip()


def _first_key(raw: str) -> str:
    if not raw:
        return ""
    if raw.startswith("["):
        try:
            parsed = json.loads(raw)
            return str(parsed[0]) if parsed else ""
        except json.JSONDecodeError:
            return ""
    return raw.split(",")[0].strip()


API_KEY = _first_key(_RAW_KEYS)
HEADERS = {"X-API-Key": API_KEY} if API_KEY else {}

ASPIRIN = "CC(=O)Oc1ccccc1C(=O)O"
ASPIRIN_SODIUM = "CC(=O)Oc1ccccc1C(=O)[O-].[Na+]"
ASPIRIN_ALT = "OC(=O)c1ccccc1OC(C)=O"
CAFFEINE = "CN1C=NC2=C1C(=O)N(C)C(=O)N2C"


def get(path):
    return httpx.get(f"{BASE}{path}", timeout=TIMEOUT, headers=HEADERS)


def post(path, payload):
    return httpx.post(f"{BASE}{path}", json=payload, timeout=TIMEOUT, headers=HEADERS)


class CapabilitiesTest(unittest.TestCase):
    def test_rdkit_engine_is_boolean(self):
        """engines 的值必须是布尔：后端 EngineCapabilities 是 Map<String, Boolean>，
        返回对象会让整个能力矩阵反序列化失败，12 个工具全部判不出可用性。"""
        body = get("/v1/evaluate/capabilities").json()
        self.assertIsInstance(body["engines"]["rdkit"], bool)
        self.assertTrue(body["engines"]["rdkit"])
        for key in ("crem", "p2rank", "vina", "admet"):
            self.assertIsInstance(body["engines"][key], bool)

    def test_endpoints_declared(self):
        body = get("/v1/evaluate/capabilities").json()
        endpoints = body["endpoints"]
        self.assertEqual(endpoints["/v1/evaluate/properties"], "READY")
        self.assertEqual(endpoints["/v1/chemistry/standardize"], "READY")
        self.assertEqual(endpoints["/v1/evaluate/capabilities"], "READY")
        # 未实现的必须明确写着 NOT_IMPLEMENTED，不能含糊
        self.assertEqual(endpoints["/v1/generate"], "NOT_IMPLEMENTED")

    def test_metric_catalog_is_honest(self):
        body = get("/v1/evaluate/capabilities").json()
        supported = body["metrics"]["supported"]
        unsupported = body["metrics"]["unsupported"]
        self.assertIn("MOL_WT", supported)
        self.assertIn("COMPLEXITY_PENALTY", supported)
        # SA_SCORE 拿不到数据文件，必须显式列入不可用并给出原因
        self.assertNotIn("SA_SCORE", supported)
        self.assertIn("SA_SCORE", unsupported)
        self.assertIn("fpscores", unsupported["SA_SCORE"])

    def test_models_structure(self):
        body = get("/v1/evaluate/capabilities").json()
        for key in ("dti", "ddi", "ppi"):
            model = body["models"][key]
            self.assertIsInstance(model["ready"], bool)
            self.assertIsInstance(model["weightsPresent"], bool)

    def test_ready_means_usable_not_loaded(self):
        """`ready` 必须是「权重在且没加载失败」，不是「已加载」。

        本服务模型懒加载，「权重在但还没加载」是正常可用状态。把它判成不可用，
        会让权重齐全的算法在工具面板上永久灰掉 —— 而且没人会怀疑是能力矩阵的问题。
        """
        body = get("/v1/evaluate/capabilities").json()
        for key, model in body["models"].items():
            if model["weightsPresent"]:
                self.assertTrue(
                    model["ready"],
                    f"{key} 权重存在却被判不可用（ready=false, loaded={model['loaded']}）",
                )
                self.assertIsNone(model["error"], f"{key} 可用却带了错误原因")
            else:
                self.assertFalse(model["ready"], f"{key} 权重缺失却被判可用")
                self.assertTrue(model["error"], f"{key} 不可用却没给原因（用户只能干瞪眼）")


class PropertiesTest(unittest.TestCase):
    def _post(self, molecules, metrics=None, include_diversity=False):
        payload = {"molecules": molecules}
        if metrics is not None:
            payload["metrics"] = metrics
        if include_diversity:
            payload["includeDiversity"] = True
        return post("/v1/evaluate/properties", payload)

    def test_aspirin_values(self):
        resp = self._post([{"ref": "a1", "smiles": ASPIRIN}])
        self.assertEqual(resp.status_code, 200, resp.text)
        body = resp.json()
        self.assertEqual(body["status"], "SUCCEEDED")

        row = body["results"][0]
        self.assertTrue(row["success"])
        self.assertAlmostEqual(row["metrics"]["MOL_WT"]["value"], 180.16, places=1)
        self.assertEqual(row["inchikey"], "BSYNRYMUTXBXSQ-UHFFFAOYSA-N")
        self.assertEqual(row["formula"], "C9H8O4")
        self.assertEqual(row["murckoScaffold"], "c1ccccc1")
        self.assertEqual(row["ref"], "a1")

    def test_caffeine_inchikey(self):
        body = self._post([{"ref": "c1", "smiles": CAFFEINE}]).json()
        self.assertEqual(body["results"][0]["inchikey"], "RYYVLZVUVIJVGH-UHFFFAOYSA-N")
        self.assertAlmostEqual(body["results"][0]["metrics"]["MOL_WT"]["value"], 194.19, places=1)

    def test_qed_and_lipinski_shapes(self):
        body = self._post([{"ref": "a1", "smiles": ASPIRIN}]).json()
        metrics = body["results"][0]["metrics"]
        self.assertGreater(metrics["QED"]["value"], 0.0)
        self.assertLessEqual(metrics["QED"]["value"], 1.0)
        # LIPINSKI_PASS 的值是"未违反的规则条数"（满 4）
        self.assertLessEqual(metrics["LIPINSKI_PASS"]["value"], 4)
        self.assertIn("violations", metrics["LIPINSKI_PASS"]["detail"])
        self.assertIsInstance(metrics["PAINS_HITS"]["detail"], list)
        self.assertIsInstance(metrics["BRENK_HITS"]["detail"], list)

    def test_invalid_smiles_does_not_break_batch(self):
        """一个坏分子不能让整批失败 —— 200 个里错 1 个就全废是不可接受的。"""
        resp = self._post([
            {"ref": "ok", "smiles": ASPIRIN},
            {"ref": "bad", "smiles": "这不是SMILES"},
            {"ref": "ok2", "smiles": CAFFEINE},
        ])
        body = resp.json()
        self.assertEqual(body["status"], "PARTIAL")
        flags = {r["ref"]: r["success"] for r in body["results"]}
        self.assertTrue(flags["ok"])
        self.assertFalse(flags["bad"])
        self.assertTrue(flags["ok2"])
        bad = next(r for r in body["results"] if r["ref"] == "bad")
        self.assertTrue(bad["error"])

    def test_all_invalid_returns_failed(self):
        body = self._post([{"ref": "x", "smiles": "###"}]).json()
        self.assertEqual(body["status"], "FAILED")
        self.assertFalse(body["results"][0]["success"])

    def test_empty_smiles_rejected_as_item_failure(self):
        body = self._post([{"ref": "e", "smiles": "   "}]).json()
        self.assertFalse(body["results"][0]["success"])
        self.assertIn("为空", body["results"][0]["error"])

    def test_model_metric_rejected_whole_batch(self):
        """SA_SCORE 需要数据文件而我们没有 —— 必须 400 整批拒绝，
        而不是静默跳过（否则调用方以为算过了）。"""
        resp = self._post([{"ref": "a", "smiles": ASPIRIN}], metrics=["MOL_WT", "SA_SCORE"])
        self.assertEqual(resp.status_code, 400)
        detail = resp.json()["detail"]
        self.assertEqual(detail["error"], "UNSUPPORTED_METRIC")
        rejected = {m["metric"]: m["reason"] for m in detail["metrics"]}
        self.assertIn("SA_SCORE", rejected)
        self.assertIn("fpscores", rejected["SA_SCORE"])

    def test_unknown_metric_rejected(self):
        resp = self._post([{"ref": "a", "smiles": ASPIRIN}], metrics=["NOT_A_METRIC"])
        self.assertEqual(resp.status_code, 400)

    def test_diversity_computed_within_batch(self):
        """同批里两个高度相似的分子，maxSimilarity 应显著 > 0。"""
        body = self._post(
            [
                {"ref": "a", "smiles": ASPIRIN},
                {"ref": "b", "smiles": ASPIRIN_ALT},
            ],
            include_diversity=True,
        ).json()
        sims = [r["diversity"]["maxSimilarity"] for r in body["results"]]
        self.assertTrue(all(s > 0.9 for s in sims), sims)

    def test_batch_limit(self):
        many = [{"ref": str(i), "smiles": ASPIRIN} for i in range(501)]
        resp = self._post(many)
        self.assertEqual(resp.status_code, 422)


class StandardizeTest(unittest.TestCase):
    def _post(self, molecules, threshold=None):
        payload = {"molecules": molecules}
        if threshold is not None:
            payload["dedupThreshold"] = threshold
        return post("/v1/chemistry/standardize", payload)

    def test_same_molecule_two_notations_same_inchikey(self):
        """字符串比对会把这两种写法当成两个候选，规范化后必须一致。"""
        body = self._post([
            {"ref": "a", "smiles": ASPIRIN},
            {"ref": "b", "smiles": ASPIRIN_ALT},
        ]).json()
        keys = {r["ref"]: r["inchikey"] for r in body["results"]}
        self.assertEqual(keys["a"], keys["b"])

    def test_salt_stripped(self):
        """钠盐去盐后应与游离酸同一 InChIKey。"""
        body = self._post([
            {"ref": "acid", "smiles": ASPIRIN},
            {"ref": "salt", "smiles": ASPIRIN_SODIUM},
        ]).json()
        keys = {r["ref"]: r["inchikey"] for r in body["results"]}
        self.assertEqual(keys["acid"], keys["salt"])
        salt_row = next(r for r in body["results"] if r["ref"] == "salt")
        self.assertNotIn("Na", salt_row["formula"])

    def test_dedup_clusters_by_threshold(self):
        body = self._post(
            [
                {"ref": "a", "smiles": ASPIRIN},
                {"ref": "b", "smiles": ASPIRIN_ALT},
                {"ref": "c", "smiles": CAFFEINE},
            ],
            threshold=0.9,
        ).json()
        dedup = body["dedup"]
        self.assertEqual(dedup["threshold"], 0.9)
        # 阿司匹林的两种写法应合成一簇，咖啡因独立成簇
        self.assertEqual(dedup["clusterCount"], 2)
        self.assertEqual(dedup["mergedCount"], 1)
        rep = {c["representative"]: c["merged"] for c in dedup["clusters"]}
        self.assertIn("b", rep["a"])
        self.assertEqual(rep["c"], [])

    def test_invalid_item_isolated(self):
        body = self._post([
            {"ref": "ok", "smiles": ASPIRIN},
            {"ref": "bad", "smiles": "@@@"},
        ]).json()
        self.assertEqual(body["status"], "PARTIAL")
        flags = {r["ref"]: r["success"] for r in body["results"]}
        self.assertTrue(flags["ok"])
        self.assertFalse(flags["bad"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
