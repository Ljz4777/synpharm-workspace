package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ConstraintRuleDto;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.ConstraintSetUpsertRequest;
import com.synpharm.design.api.dto.RuleValidationResponse;

import java.util.List;
import java.util.Map;

/**
 * 约束集用例。
 *
 * <p>约束集有属主（{@code constraint_set.user_id}），因此每个端点都必须做归属校验，
 * 失败一律 404（红线 R4）。
 *
 * @author SynPharm Team
 */
public interface ConstraintAppService {

    /** 我的约束集列表（按状态可选过滤） */
    PageResult<ConstraintSetResponse> list(String status, int page, int pageSize);

    /** 详情（含规则）；不存在或非本人 → 404 */
    ConstraintSetResponse get(Long id);

    /** 新建（可内联规则），初始状态 DRAFT */
    ConstraintSetResponse create(ConstraintSetUpsertRequest request);

    /** 编辑；仅 DRAFT 可改，整集替换规则 */
    ConstraintSetResponse update(Long id, ConstraintSetUpsertRequest request);

    /** 激活：先自检规则，通过后 DRAFT → ACTIVE */
    ConstraintSetResponse activate(Long id);

    /** 归档：→ ARCHIVED */
    ConstraintSetResponse archive(Long id);

    /** 规则自检（编辑器实时反馈，正常交互不报错码） */
    RuleValidationResponse validate(List<ConstraintRuleDto> rules);

    /** 可用枚举字典 */
    Map<String, Object> meta();
}
