package com.synpharm.design.api.dto;

import com.synpharm.design.support.editing.EditDtos;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * E1 缺口诊断响应（06 §5.7）。
 *
 * <p>{@code noGaps} 必须回传：它是 E3 回归守卫的保护名单，
 * 前端也要据此展示「哪些维度已经到手、别在编辑中打坏」。
 *
 * @author SynPharm Team
 */
@Data
public class EditGapResponse {

    private Long id;
    private String reportNo;
    private Long candidateId;
    private Integer versionNo;
    private Long evalFreezeId;

    /** 待修缺口（按建议顺序） */
    private List<Map<String, Object>> gaps;

    /** 已达标维度 —— 保护名单 */
    private List<String> noGaps;

    /** 当前手段修不了的缺口 */
    private List<Map<String, Object>> unfixable;

    /** 建议修复顺序（dimension 列表） */
    private List<String> recommendedOrder;

    private String note;

    /** E2 匹配出的候选动作（top-3/维度） */
    private List<Map<String, Object>> actions;

    /** 归因能力说明 —— 让用户知道「为什么没有归因」 */
    private String attributionNote = "结构归因需要归因模型，当前未接入；缺口只精确到指标与维度";
}
