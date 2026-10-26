package com.synpharm.design.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 编辑项目请求（契约 {@code ProjectPatchRequest}）。
 *
 * <p>{@code version} 用于乐观锁占位：{@code design_project} 没建 {@code version} 列，
 * 因此当前实现只接受该字段但不做校验（不改契约形状，留待加列后启用）。
 *
 * <p>所有字段都是「给出才改」：{@code null} 表示保持原值，注意无法用本接口把
 * 配额清空回「不限」，需要的话请显式传 0（配额 0 会被基线判为非法）。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectPatchRequest {

    @Size(max = 200, message = "name 最长 200 字符")
    private String name;

    private String goalText;

    private Long diseaseId;

    private Long modalityId;

    /** 作用机制码，须属知识库 {@code MOA} 分类 */
    private String moaCode;

    /** 生成后置闸门：最少候选数 */
    private Integer minCandidates;

    /** GPU 小时配额 */
    private BigDecimal quotaGpuHours;

    /** 湿实验名额 */
    private Integer quotaWetLab;

    /** 决策名额档位：RATIO / ABSOLUTE */
    private String decisionCapacityMode;

    /** 档位参数：RATIO 时 0~1，ABSOLUTE 时正整数 */
    private BigDecimal decisionCapacityValue;

    private Integer version;
}
