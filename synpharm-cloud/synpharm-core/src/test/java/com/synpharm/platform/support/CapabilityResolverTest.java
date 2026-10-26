package com.synpharm.platform.support;

import com.synpharm.platform.infrastructure.client.EngineCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力判定核心测试（红线 R1）。
 *
 * <p>这一层判错的后果是两个方向：假阴是「引擎部署了却显示不可用」，假阳是「显示可用但一点就失败」。
 * 假阳更危险，所以**判不出必须返回 null**，由调用方落成「不可用 + 原因」，绝不能当成可用。
 *
 * @author SynPharm Team
 */
class CapabilityResolverTest {

    private final CapabilityResolver resolver = new CapabilityResolver();

    private EngineCapabilities capabilities(Map<String, Boolean> engines, Map<String, Boolean> models) {
        EngineCapabilities capabilities = new EngineCapabilities();
        capabilities.setEngines(engines);
        Map<String, EngineCapabilities.ModelCapability> modelMap = new HashMap<>();
        models.forEach((key, ready) -> {
            EngineCapabilities.ModelCapability model = new EngineCapabilities.ModelCapability();
            model.setReady(ready);
            modelMap.put(key, model);
        });
        capabilities.setModels(modelMap);
        return capabilities;
    }

    @Test
    @DisplayName("engines 直接命中：rdkit=true → 可用；vina=false → 不可用")
    void resolvesFromEngines() {
        EngineCapabilities caps = capabilities(Map.of("rdkit", true, "vina", false), Map.of());

        assertThat(resolver.resolve("rdkit", "rdkit", caps)).isTrue();
        assertThat(resolver.resolve("vina", "vina", caps)).isFalse();
    }

    @Test
    @DisplayName("models.* 走 key.split('.')[1] 再取 ready")
    void resolvesFromModels() {
        EngineCapabilities caps = capabilities(Map.of(), Map.of("dti", true, "ppi", false));

        assertThat(resolver.resolve("models.dti", "kan_modti", caps)).isTrue();
        assertThat(resolver.resolve("models.ppi", "flashppi", caps)).isFalse();
    }

    @Test
    @DisplayName("capability_key 判不出时回退 requires_engine（种子数据漂移的兜底）")
    void fallsBackToRequiresEngine() {
        EngineCapabilities caps = capabilities(Map.of("p2rank", true, "crem", false), Map.of());

        // 种子里 pocket_detect 的 capability_key 写的是 pockets，而引擎键其实是 p2rank
        assertThat(resolver.resolve("pockets", "p2rank", caps)).isTrue();
        assertThat(resolver.resolve("generation", "crem", caps)).isFalse();
    }

    @Test
    @DisplayName("engines 命中时不再回退，避免用 requires_engine 覆盖更精确的判定")
    void doesNotFallBackWhenEnginesHit() {
        EngineCapabilities caps = capabilities(Map.of("admet", false, "admet_ai", true), Map.of());

        assertThat(resolver.resolve("admet", "admet_ai", caps)).isFalse();
    }

    @Test
    @DisplayName("都判不出且无 requires_engine → null（判不出 ≠ 可用）")
    void returnsNullWhenUnresolvable() {
        EngineCapabilities caps = capabilities(Map.of("rdkit", true), Map.of());

        assertThat(resolver.resolve("nonexistent", null, caps)).isNull();
        assertThat(resolver.resolve(null, null, caps)).isNull();
    }

    @Test
    @DisplayName("判不出时必须给出可展示的原因（不允许出现没有原因的红点）")
    void explainsUnresolved() {
        assertThat(resolver.explainUnresolved(null, null)).contains("未声明");
        assertThat(resolver.explainUnresolved("pockets", null)).contains("pockets");
        assertThat(resolver.explainUnresolved(null, "p2rank")).contains("p2rank");
    }
}
