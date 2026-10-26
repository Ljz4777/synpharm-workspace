package com.synpharm.design.application;

import com.synpharm.design.api.dto.DecisionVetoRuleRequest;
import com.synpharm.design.api.dto.DecisionVetoRuleResponse;
import java.util.List;

/**
 * 否决规则应用服务（07 §8，E2）。
 *
 * <p>{@code projectId = 0} 的全局默认规则<b>只读</b>：它们是所有项目共享的底线
 * （必测未验证、FTO 高风险等），允许某个项目关掉就等于允许绕过底线。
 * 想调整请为该项目新建规则。
 *
 * @author SynPharm Team
 */
public interface DecisionVetoRuleAppService {

    /** 列表：全局默认 + 该项目自有 */
    List<DecisionVetoRuleResponse> list(Long projectId);

    /** 为项目新建规则 */
    DecisionVetoRuleResponse create(Long projectId, DecisionVetoRuleRequest request);

    /** 修改项目级规则 */
    DecisionVetoRuleResponse update(Long ruleId, DecisionVetoRuleRequest request);

    /** 删除项目级规则（软删由调用方决定；这里直接删，规则无历史价值） */
    void delete(Long ruleId);
}