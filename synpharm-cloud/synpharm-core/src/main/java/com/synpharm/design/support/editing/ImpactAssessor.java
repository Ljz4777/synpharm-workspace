package com.synpharm.design.support.editing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * E3 影响面评估（回归守卫）。
 *
 * <p>这里回答的唯一问题是：<b>这个动作会不会把已经达标的维度打坏</b>。
 * 打坏了就该 BLOCK，而不是「先改了再评估」—— 后者会让编辑轮次被浪费在
 * 必然回退的方案上。
 *
 * <p>三档判定：
 * <ul>
 *   <li>{@code MUST_DROP} 且该维度<b>当前已达标</b> → {@link #BLOCK}（不能拿已到手的东西去换）</li>
 *   <li>{@code MUST_DROP} 但该维度本来就没达标 → {@link #WARN}（反正在修，掉了不亏）</li>
 *   <li>{@code WATCH} → {@link #WARN}</li>
 * </ul>
 *
 * <p><b>{@code predictedDelta} 恒为 null</b>：预测「人源化会让亲和力掉多少」
 * 需要动作→指标的量化模型，当前没有。给出 WATCH/MUST_DROP 是定性的、可解释的；
 * 编一个 -0.3 的数字会让用户以为系统量过了。
 *
 * @author SynPharm Team
 */
@Component
public class ImpactAssessor {

    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String BLOCK = "BLOCK";

    public static final String MUST_DROP = "MUST_DROP";
    public static final String WATCH = "WATCH";

    /**
     * @param option     E2 匹配出的动作
     * @param noGaps     E1 诊断出的已达标维度（保护名单）
     * @param isParentPassed 判断某维度当前是否达标（由调用方按维度反查分数）
     */
    public EditDtos.Impact assess(EditDtos.ActionOption option,
                                  List<String> noGaps,
                                  java.util.function.Predicate<String> isParentPassed) {
        List<EditDtos.ProtectedImpact> impacts = new ArrayList<>();
        boolean blocked = false;
        boolean warned = false;
        List<String> blockReasons = new ArrayList<>();

        Map<String, Object> sideEffects = option.sideEffects() == null ? Map.of() : option.sideEffects();
        for (Map.Entry<String, Object> e : sideEffects.entrySet()) {
            String dimension = e.getKey();
            String risk = e.getValue() == null ? WATCH : String.valueOf(e.getValue()).trim().toUpperCase();
            if (!MUST_DROP.equals(risk) && !WATCH.equals(risk)) {
                continue;
            }
            boolean passed = isParentPassed.test(dimension);

            String note;
            if (MUST_DROP.equals(risk) && passed) {
                note = "该维度当前已达标，此动作很可能把它打坏 —— 不能拿已到手的东西去换";
                blocked = true;
                blockReasons.add(dimension);
            } else if (MUST_DROP.equals(risk)) {
                note = "该维度当前未达标，此动作可能进一步降低它，但也在本轮修复范围内";
                warned = true;
            } else {
                note = "此动作可能轻微影响该维度，改完需复核";
                warned = true;
            }
            impacts.add(new EditDtos.ProtectedImpact(dimension, risk, passed, null, note));
        }

        String guardResult = blocked ? BLOCK : warned ? WARN : PASS;
        String blockReason = blocked
                ? "动作 " + option.actionCode() + " 会打坏已达标维度：" + String.join("、", blockReasons)
                  + "。如确需执行，请由负责人手动解锁并写明理由"
                : null;

        return new EditDtos.Impact(option.actionCode(), guardResult, blockReason, List.copyOf(impacts));
    }

    /** 无副作用声明时的兜底：PASS，但仍要提示「没有声明不等于没有影响」 */
    public EditDtos.Impact assessUnknown(EditDtos.ActionOption option) {
        return new EditDtos.Impact(option.actionCode(), PASS, null, List.of(
                new EditDtos.ProtectedImpact(
                        "*", WATCH, false, null,
                        "该动作没有声明副作用矩阵，只能按「未声明」处理，改完必须复核全部已达标维度")));
    }
}
