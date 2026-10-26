package com.synpharm.design.api.dto;

import lombok.Data;

/**
 * 否决命中（07 §5.1）。
 *
 * <p>{@code evidenceRef} 指回证据包里的具体字段 —— 只说「被否决」不说依据，
 * 用户无法判断该补数据还是该换分子。
 *
 * @author SynPharm Team
 */
@Data
public class VetoHitResponse {

    private String ruleCode;
    /** BLOCK / WARN */
    private String severity;
    private String message;
    private String evidenceRef;
}