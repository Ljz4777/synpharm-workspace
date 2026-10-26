package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 约束集新建/编辑请求（契约 {@code ConstraintSetUpsert}）。
 *
 * @author SynPharm Team
 */
@Data
public class ConstraintSetUpsertRequest {

    @NotBlank(message = "name 不能为空")
    @Size(max = 200, message = "name 最长 200 字符")
    private String name;

    @Size(max = 500, message = "description 最长 500 字符")
    private String description;

    /** 整集替换：传了就覆盖，不传则保持原规则不变 */
    private List<ConstraintRuleDto> rules;

    /** 版本号；不传时新建取 1、编辑保持原值 */
    private Integer version;
}
