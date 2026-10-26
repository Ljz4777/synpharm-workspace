package com.synpharm.design.api.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 优化目标（契约 {@code Objective}）：{@code {dimension, operator, threshold, unit, weight}}。
 *
 * <p>{@code dimension} 必须是 8 大评估维度之一（契约 §4① 的校验顺序里明确要求）。
 *
 * @author SynPharm Team
 */
@Data
public class ObjectiveDto {

    private String dimension;

    private String operator;

    private BigDecimal threshold;

    private String unit;

    private Integer weight;
}
