package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人工调整（D6，07 §2.1）。
 *
 * <p>{@code overrideReason} 必填且<b>不允许被前端省略</b>：调整是人对机器的覆盖，
 * 没有理由的覆盖事后无法复盘——「谁改的、为什么改」恰恰是决策记录最有价值的部分。
 *
 * <p>能调整成什么是有边界的：被 BLOCK 级否决、或硬约束复核不通过的候选，
 * 改成「入选」会被 409 拦下（否决不可绕过）。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionOverrideRequest {

    /** SELECTED / BACKUP / REJECTED / DEFERRED */
    @NotBlank(message = "outcome 不能为空")
    @Pattern(regexp = "SELECTED|BACKUP|REJECTED|DEFERRED", message = "outcome 取值必须是 SELECTED/BACKUP/REJECTED/DEFERRED")
    private String outcome;

    @NotBlank(message = "必须填写调整理由：没有理由的调整无法追溯")
    @Size(max = 500, message = "调整理由最长 500 字符")
    private String overrideReason;
}
