package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;

/**
 * 风险接受（D7，07 §2.1）。
 *
 * <p>粒度是<b>逐候选逐风险点</b>：一个候选有三个风险，决策者只接受其中两个，
 * 第三个仍要补数据 —— 整体接受会把这个区别抹掉。
 *
 * <p>{@code mustVerify} 的风险点在这里会被 409 拦下，不是文档约定而是硬校验：
 * 「必须验证」一旦可以被「接受」按钮跳过，它就退化成了建议。
 *
 * @author SynPharm Team
 */
@Data
public class RiskAcceptanceRequest {

    @NotBlank(message = "riskCode 不能为空")
    @Size(max = 40, message = "riskCode 最长 40 字符")
    private String riskCode;

    @NotBlank(message = "接受风险必须填写理由")
    @Size(max = 1000, message = "接受理由最长 1000 字符")
    private String reason;

    /** 有效期；为空表示本决策内长期有效 */
    private LocalDate validUntil;
}
