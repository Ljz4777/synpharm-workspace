package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.DecisionVetoRuleRequest;
import com.synpharm.design.api.dto.DecisionVetoRuleResponse;
import com.synpharm.design.application.DecisionVetoRuleAppService;
import com.synpharm.design.domain.model.DecisionVetoRule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.infrastructure.persistence.DecisionVetoRuleMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 否决规则 CRUD（E2）。
 *
 * <p>全局规则（{@code project_id = 0}）只读：它们是所有项目共享的底线，
 * 允许某个项目改掉等于允许绕过底线。要调整就为该项目新建一条。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionVetoRuleAppServiceImpl implements DecisionVetoRuleAppService {

    /** 全部允许的规则码；未知码会让判定层跳过，所以入口就拦住 */
    private static final List<String> KNOWN_CODES = List.of(
            "MUST_VERIFY_UNVERIFIED", "BUDGET_EXCEEDED", "FTO_RISK_HIGH",
            "DATA_INCOMPLETE", "HARD_RULE_VIOLATED", "MANUAL_VETO");

    private final DecisionVetoRuleMapper ruleMapper;
    private final DesignProjectMapper projectMapper;
    private final JsonCodec jsonCodec;

    @Override
    public List<DecisionVetoRuleResponse> list(Long projectId) {
        Long userId = CurrentUser.requireUserId();
        loadOwnedProject(projectId, userId);

        List<DecisionVetoRule> rules = ruleMapper.selectList(
                Wrappers.<DecisionVetoRule>lambdaQuery()
                        .in(DecisionVetoRule::getProjectId, 0L, projectId)
                        .orderByAsc(DecisionVetoRule::getSortNo)
                        .orderByAsc(DecisionVetoRule::getId));
        List<DecisionVetoRuleResponse> out = new ArrayList<>();
        for (DecisionVetoRule r : rules) {
            out.add(toResponse(r));
        }
        return out;
    }

    @Override
    public DecisionVetoRuleResponse create(Long projectId, DecisionVetoRuleRequest request) {
        Long userId = CurrentUser.requireUserId();
        loadOwnedProject(projectId, userId);
        validate(request);

        DecisionVetoRule rule = new DecisionVetoRule();
        rule.setProjectId(projectId);
        applyRequest(rule, request);
        ruleMapper.insert(rule);
        log.info("新增否决规则 project={} code={} severity={}", projectId, rule.getRuleCode(), rule.getSeverity());
        return toResponse(rule);
    }

    @Override
    public DecisionVetoRuleResponse update(Long ruleId, DecisionVetoRuleRequest request) {
        DecisionVetoRule rule = loadEditableRule(ruleId);
        validate(request);
        applyRequest(rule, request);
        ruleMapper.updateById(rule);
        return toResponse(rule);
    }

    @Override
    public void delete(Long ruleId) {
        DecisionVetoRule rule = loadEditableRule(ruleId);
        ruleMapper.deleteById(rule.getId());
        log.info("删除否决规则 id={} code={}", rule.getId(), rule.getRuleCode());
    }

    // ------------------------------------------------------------------ 内部

    private DecisionVetoRule loadEditableRule(Long ruleId) {
        Long userId = CurrentUser.requireUserId();
        DecisionVetoRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BizException(ErrorCode.DECISION_VETO_RULE_NOT_FOUND,
                    "否决规则不存在：" + ruleId, Map.of("ruleId", ruleId));
        }
        if (rule.getProjectId() == null || rule.getProjectId() == 0L) {
            throw new BizException(ErrorCode.DECISION_VETO_RULE_GLOBAL_IMMUTABLE,
                    "全局默认规则（#" + ruleId + "）只读，请为特定项目新建规则",
                    Map.of("ruleId", ruleId));
        }
        loadOwnedProject(rule.getProjectId(), userId);
        return rule;
    }

    private DesignProject loadOwnedProject(Long projectId, Long userId) {
        DesignProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_NOT_FOUND,
                    "项目不存在：" + projectId, Map.of("projectId", projectId));
        }
        OwnershipGuard.assertOwned(userId, project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", projectId));
        return project;
    }

    /**
     * 入口校验。
     *
     * <p>未知 {@code ruleCode} 直接拒绝：判定层遇到不认识的码会「跳过并告警」，
     * 那是为了不让历史脏数据阻断流程；但新写进来的未知码属于配置错误，必须在入口拦住。
     */
    private void validate(DecisionVetoRuleRequest request) {
        if (!KNOWN_CODES.contains(request.getRuleCode())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "未知的否决规则码「" + request.getRuleCode() + "」，可选：" + String.join("、", KNOWN_CODES),
                    Map.of("ruleCode", request.getRuleCode()));
        }
        String severity = request.getSeverity();
        if (severity != null && !"BLOCK".equals(severity) && !"WARN".equals(severity)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "severity 只能是 BLOCK 或 WARN", Map.of("severity", severity));
        }
        if (request.getParamsJson() != null && !request.getParamsJson().isBlank()
                && jsonCodec.readMap(request.getParamsJson()) == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "paramsJson 不是合法的 JSON 对象", Map.of());
        }
    }

    private void applyRequest(DecisionVetoRule rule, DecisionVetoRuleRequest request) {
        rule.setRuleCode(request.getRuleCode());
        rule.setSeverity(request.getSeverity() == null ? "BLOCK" : request.getSeverity());
        rule.setDimension(request.getDimension());
        rule.setThresholdNum(request.getThresholdNum());
        rule.setParamsJson(request.getParamsJson());
        rule.setHumanReadable(request.getHumanReadable());
        rule.setEnabled(request.getEnabled() == null || request.getEnabled() ? 1 : 0);
        rule.setSortNo(request.getSortNo() == null ? 100 : request.getSortNo());
    }

    private DecisionVetoRuleResponse toResponse(DecisionVetoRule r) {
        DecisionVetoRuleResponse resp = new DecisionVetoRuleResponse();
        resp.setId(r.getId());
        resp.setProjectId(r.getProjectId());
        resp.setGlobal(r.getProjectId() == null || r.getProjectId() == 0L);
        resp.setRuleCode(r.getRuleCode());
        resp.setSeverity(r.getSeverity());
        resp.setDimension(r.getDimension());
        resp.setThresholdNum(r.getThresholdNum());
        resp.setParamsJson(r.getParamsJson());
        resp.setHumanReadable(r.getHumanReadable());
        resp.setEnabled(r.getEnabled() == null || r.getEnabled() == 1);
        resp.setSortNo(r.getSortNo());
        resp.setCreatedAt(r.getCreatedAt());
        return resp;
    }
}
