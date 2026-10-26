package com.synpharm.design.support.editing;

import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.EditActionMapping;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * E2 策略匹配：按缺口维度从映射表挑动作，给出 top-3。
 *
 * <p>排序口径（不回退到「谁先建表谁优先」这种不可解释的顺序）：
 * <ol>
 *   <li>{@code priority} 小的优先 —— 这是人工可调的显式序；</li>
 *   <li>同 priority 时 {@code successRate} 高的优先；</li>
 *   <li>再同则按 {@code actionCode} 字典序，保证同一份数据两次匹配结果一致。</li>
 * </ol>
 *
 * <p><b>型式过滤</b>：{@code appliesToModality} 为 {@code *} 表示全部适用，
 * 否则按逗号拆分后精确匹配候选的分子形式。形式不匹配的动作绝不出现 ——
 * 给一个小分子推荐 HUMANIZATION 是纯粹的噪音。
 *
 * @author SynPharm Team
 */
@Component
@RequiredArgsConstructor
public class ActionMatcher {

    /** 最多给出几个候选动作 */
    public static final int TOP_N = 3;

    private final JsonCodec jsonCodec;

    /**
     * @param gaps              E1 诊断出的缺口
     * @param mappings          已启用的映射规则
     * @param modalityCode      候选的分子形式（如 SMALL_MOLECULE / ANTIBODY）
     */
    public List<EditDtos.ActionOption> match(List<EditDtos.Gap> gaps,
                                             List<EditActionMapping> mappings,
                                             String modalityCode) {
        List<EditDtos.ActionOption> out = new ArrayList<>();
        if (gaps == null || gaps.isEmpty() || mappings == null) {
            return out;
        }

        for (EditDtos.Gap gap : gaps) {
            List<EditActionMapping> candidates = new ArrayList<>();
            for (EditActionMapping m : mappings) {
                if (m.getEnabled() != null && m.getEnabled() == 0) {
                    continue;
                }
                if (m.getDeleted() != null && m.getDeleted() == 1) {
                    continue;
                }
                if (!gap.dimension().equals(m.getGapDimension())) {
                    continue;
                }
                if (!modalityApplies(m.getAppliesToModality(), modalityCode)) {
                    continue;
                }
                candidates.add(m);
            }

            candidates.sort(Comparator
                    .comparingInt((EditActionMapping m) -> m.getPriority() == null ? 100 : m.getPriority())
                    .thenComparing((EditActionMapping m) -> m.getSuccessRate(),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(EditActionMapping::getPrimaryAction));

            List<EditDtos.ActionOption> forGap = new ArrayList<>();
            for (EditActionMapping m : candidates) {
                forGap.add(new EditDtos.ActionOption(
                        gap.dimension(), m.getPrimaryAction(), false,
                        m.getSuccessRate(),
                        m.getSampleSize() == null ? 0 : m.getSampleSize(),
                        parseSideEffects(m.getSideEffectsJson())));
                if (m.getFallbackAction() != null && !m.getFallbackAction().isBlank()) {
                    forGap.add(new EditDtos.ActionOption(
                            gap.dimension(), m.getFallbackAction(), true,
                            m.getSuccessRate(),
                            m.getSampleSize() == null ? 0 : m.getSampleSize(),
                            parseSideEffects(m.getSideEffectsJson())));
                }
            }

            // 同一维度最多给 TOP_N 个，且不重复推荐同一个动作码
            List<String> seen = new ArrayList<>();
            int taken = 0;
            for (EditDtos.ActionOption option : forGap) {
                if (taken >= TOP_N) {
                    break;
                }
                if (seen.contains(option.actionCode())) {
                    continue;
                }
                seen.add(option.actionCode());
                out.add(option);
                taken++;
            }
        }
        return out;
    }

    public static boolean modalityApplies(String appliesTo, String modalityCode) {
        if (appliesTo == null || appliesTo.isBlank() || "*".equals(appliesTo.trim())) {
            return true;
        }
        if (modalityCode == null || modalityCode.isBlank()) {
            // 形式未知时只放行「全部适用」的规则，不猜
            return false;
        }
        String target = modalityCode.trim().toUpperCase();
        for (String part : appliesTo.split(",")) {
            if (part.trim().toUpperCase().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> parseSideEffects(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = jsonCodec.readMap(json);
        return parsed == null ? Map.of() : parsed;
    }
}
