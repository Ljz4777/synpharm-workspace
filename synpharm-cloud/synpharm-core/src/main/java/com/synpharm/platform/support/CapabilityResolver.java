package com.synpharm.platform.support;

import com.synpharm.platform.infrastructure.client.EngineCapabilities;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把 {@code design_tool} 的一行映射到引擎能力矩阵上（红线 R1 的判定核心）。
 *
 * <p>解析顺序（计算层契约 04 §2.2 的映射规则 + 一处兜底）：
 * <ol>
 *   <li>{@code engines[capabilityKey]} —— 直接命中引擎键，如 {@code rdkit} / {@code vina}；</li>
 *   <li>{@code models[capabilityKey.split('.')[1]]} —— 命中模型键，如 {@code models.dti} → {@code dti}.{@code ready}；</li>
 *   <li><b>兜底</b> {@code engines[requiresEngine]}：仅当前两步都判不出时启用，并打 WARN。</li>
 * </ol>
 *
 * <p>为什么需要第 3 步：种子里有两个 {@code capability_key} 与引擎键对不上 ——
 * {@code pocket_detect} 写的是 {@code pockets}（引擎键是 {@code p2rank}）、
 * {@code molecule_generate} 写的是 {@code generation}（引擎键是 {@code crem}）。若严格只按前两步，
 * 这两行**永远**判为不可用，即便 P2Rank/CReM 已部署 —— 属"假阴"（方向比"假阳"安全，但仍然错）。
 * {@code requires_engine} 列自身的注释就是「依赖的引擎标识（**能力探测用**）」，且这两行的取值
 * 恰好是正确引擎键，因此用它兜底是有依据的；WARN 日志让这类数据漂移保持可见。
 *
 * <p>返回 {@code null} 表示**判不出**（无键、或键在矩阵中不存在）。调用方必须把它当
 * 「不可用 + 说明原因」处理，绝不接受"判不出就算可用"。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class CapabilityResolver {

    /**
     * @param capabilityKey  design_tool.capability_key，可为空
     * @param requiresEngine design_tool.requires_engine，可为空
     * @param capabilities   引擎能力矩阵
     * @return true/false；判不出返回 null
     */
    public Boolean resolve(String capabilityKey, String requiresEngine, EngineCapabilities capabilities) {
        Boolean byKey = byCapabilityKey(capabilityKey, capabilities);
        if (byKey != null) {
            return byKey;
        }
        if (StringUtils.hasText(requiresEngine)) {
            Boolean byEngine = engine(capabilities, requiresEngine);
            if (byEngine != null) {
                log.warn("capability_key={} 在能力矩阵中判不出，已回退到 requires_engine={}；"
                        + "建议把该行的 capability_key 更正为引擎键", capabilityKey, requiresEngine);
                return byEngine;
            }
        }
        return null;
    }

    /** 判不出时给前端的说明文案（必须非空，否则 UI 只能干瞪眼） */
    public String explainUnresolved(String capabilityKey, String requiresEngine) {
        if (!StringUtils.hasText(capabilityKey) && !StringUtils.hasText(requiresEngine)) {
            return "该工具未声明 capabilityKey / requiresEngine，不参与引擎能力探测";
        }
        return "能力矩阵中不存在键 " + (StringUtils.hasText(capabilityKey) ? capabilityKey : requiresEngine)
                + "，无法判定可用性";
    }

    private Boolean byCapabilityKey(String capabilityKey, EngineCapabilities capabilities) {
        if (!StringUtils.hasText(capabilityKey)) {
            return null;
        }
        Boolean direct = engine(capabilities, capabilityKey);
        if (direct != null) {
            return direct;
        }
        int dot = capabilityKey.indexOf('.');
        if (dot <= 0 || dot == capabilityKey.length() - 1) {
            return null;
        }
        String modelKey = capabilityKey.substring(dot + 1);
        if (capabilities.getModels() == null) {
            return null;
        }
        EngineCapabilities.ModelCapability model = capabilities.getModels().get(modelKey);
        return model == null ? null : model.getReady();
    }

    private Boolean engine(EngineCapabilities capabilities, String key) {
        return capabilities.getEngines() == null ? null : capabilities.getEngines().get(key);
    }
}