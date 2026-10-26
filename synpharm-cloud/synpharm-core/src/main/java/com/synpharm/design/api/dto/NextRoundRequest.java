package com.synpharm.design.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 启动下一轮迭代请求（契约 {@code NextRoundRequest}）。
 *
 * @author SynPharm Team
 */
@Data
public class NextRoundRequest {

    @Size(max = 2000, message = "strategyNote 最长 2000 字符")
    private String strategyNote;

    /** EXPERIMENT_BEST / TOP_CANDIDATE / MANUAL —— 本轮种子的来源 */
    private String seedFrom;

    /** 回灌校准工件 ID（协议见 04 §4） */
    private String calibrationId;

    /** 是否顺带投递生成任务 */
    private Boolean autoStartGeneration;

    /** 链式投递的生成任务参数（{@code autoStartGeneration=true} 时使用） */
    private Map<String, Object> generation;
}
