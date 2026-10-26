package com.synpharm.platform.domain.model;

/**
 * 真实性边界（红线 R1）。
 *
 * <p>它描述的是「这个工具产出的数字是什么性质」，报告与评估必须据此标注：
 * REAL 真实计算 / SEMI_REAL 半经验 / HEURISTIC 启发式 / SIMULATED 模拟值。
 * <b>必填</b>，不接受默认值 —— 否则等于允许把模拟值当真实值上报。
 *
 * @author SynPharm Team
 */
public enum Realness {
    REAL,
    SEMI_REAL,
    HEURISTIC,
    SIMULATED
}