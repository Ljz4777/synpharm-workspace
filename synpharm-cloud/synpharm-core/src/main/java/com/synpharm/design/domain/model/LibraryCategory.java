package com.synpharm.design.domain.model;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;

import java.util.Arrays;

/**
 * 知识库分类（契约 {@code LibraryCategory}）。
 *
 * <p>对应 {@code design_library_item.category}，决定 {@code meta_json} 的结构。
 *
 * @author SynPharm Team
 */
public enum LibraryCategory {

    /** 疾病 */
    DISEASE,
    /** 靶点 */
    TARGET,
    /** 分子形式 */
    MODALITY,
    /** 约束模板 */
    CONSTRAINT_TEMPLATE,
    /** 优化目标 */
    OBJECTIVE,
    /** 检测类型 */
    ASSAY,
    /**
     * 作用机制（阶段 B 新增）。
     *
     * <p>基线块1 的一项、生成策略选型的第一依据。放在字典里而不是自由文本：
     * 机制填错不会有任何环节报错，只会让策略模板悄悄选错，事后无从追溯。
     */
    MOA,
    /**
     * 筛选原因（阶段 B 新增）。
     *
     * <p>生成 S6 粗筛与编辑 E5 剪枝共用同一套原因码。被筛掉的分子必须记原因，
     * 否则后期永远分不清「筛太狠」还是「采太偏」。
     */
    FILTER_REASON;

    /**
     * 解析外部传入的分类字符串；非法值返回 400 而不是落到 500。
     *
     * @param raw 请求中的分类值
     * @return 枚举
     */
    public static LibraryCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "category 不能为空");
        }
        return Arrays.stream(values())
                .filter(v -> v.name().equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                        "未知的 category: " + raw + "，可选 " + Arrays.toString(values())));
    }

    /** 宽松解析：非法值返回 null（用于查询条件，非法即查不到而不是报错） */
    public static LibraryCategory tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(v -> v.name().equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElse(null);
    }
}
