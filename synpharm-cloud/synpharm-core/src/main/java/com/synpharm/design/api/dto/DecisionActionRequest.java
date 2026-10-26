package com.synpharm.design.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 状态变更请求（confirm / defer / cancel 共用）。
 *
 * <p>确认不需要理由（点下去就是结论）；转暂缓与取消<b>需要</b> —— 它们是「这次不算数」，
 * 不写清楚为什么，下一个人只能靠猜。
 *
 * @author SynPharm Team
 */
@Data
public class DecisionActionRequest {

    @Size(max = 500, message = "说明最长 500 字符")
    private String reason;
}
