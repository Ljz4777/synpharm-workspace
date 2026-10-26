package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ConstraintRuleDto;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.ConstraintSetUpsertRequest;
import com.synpharm.design.api.dto.RuleValidationResponse;
import com.synpharm.design.application.ConstraintAppService;
import com.synpharm.design.domain.model.ConstraintRule;
import com.synpharm.design.domain.model.ConstraintSet;
import com.synpharm.design.domain.model.ConstraintStatus;
import com.synpharm.design.domain.model.EvalDimension;
import com.synpharm.design.domain.model.NumericOperator;
import com.synpharm.design.domain.model.RuleSeverity;
import com.synpharm.design.domain.model.RuleType;
import com.synpharm.design.infrastructure.persistence.ConstraintRuleMapper;
import com.synpharm.design.infrastructure.persistence.ConstraintSetMapper;
import com.synpharm.design.support.ConstraintRuleValidator;
import com.synpharm.design.support.SerialNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
/**
 * 约束集用例实现。
 *
 * <p>状态流转：DRAFT →（activate，先自检）→ ACTIVE →（archive）→ ARCHIVED。
 * 只有 DRAFT 可编辑，其余状态改动返回 {@code CONSTRAINT_SET_NOT_EDITABLE}。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConstraintAppServiceImpl implements ConstraintAppService {

    /** {@code /constraints/meta} 下发的内置过滤器目录 */
    private static final List<String> FILTER_CATALOGS =
            List.of("PAINS", "BRENK", "LIPINSKI", "VEITH", "CHEMBL_FILTER", "GSK_RULE_OF_FIVE");

    private final ConstraintSetMapper constraintSetMapper;
    private final ConstraintRuleMapper constraintRuleMapper;
    private final JsonCodec jsonCodec;

    @Override
    public PageResult<ConstraintSetResponse> list(String status, int page, int pageSize) {
        Long userId = CurrentUser.requireUserId();
        ConstraintStatus parsed = status == null ? null : ConstraintStatus.parse(status);

        Page<ConstraintSet> result = constraintSetMapper.selectPage(new Page<>(page, pageSize),
                Wrappers.lambdaQuery(ConstraintSet.class)
                        .eq(ConstraintSet::getUserId, userId)
                        .eq(parsed != null, ConstraintSet::getStatus, parsed == null ? null : parsed.name())
                        .orderByDesc(ConstraintSet::getId));

        Map<Long, List<ConstraintRuleDto>> rulesBySet = loadRules(
                result.getRecords().stream().map(ConstraintSet::getId).toList());
        return PageResult.of(result, entity -> toResponse(entity, rulesBySet.getOrDefault(entity.getId(), List.of())));
    }

    @Override
    public ConstraintSetResponse get(Long id) {
        ConstraintSet set = loadOwned(id, CurrentUser.requireUserId());
        return toResponse(set, loadRules(List.of(id)).getOrDefault(id, List.of()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConstraintSetResponse create(ConstraintSetUpsertRequest request) {
        Long userId = CurrentUser.requireUserId();
        List<ConstraintRuleDto> rules = request.getRules() == null ? List.of() : request.getRules();
        assertRulesValid(rules);

        ConstraintSet set = new ConstraintSet();
        set.setSetNo(SerialNumbers.constraintSetNo());
        set.setUserId(userId);
        set.setName(request.getName());
        set.setDescription(request.getDescription());
        set.setLogic("AND");
        set.setStatus(ConstraintStatus.DRAFT.name());
        set.setVersion(request.getVersion() == null ? 1 : request.getVersion());

        constraintSetMapper.insert(set);
        insertRules(set.getId(), rules);
        return toResponse(set, rules);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConstraintSetResponse update(Long id, ConstraintSetUpsertRequest request) {
        ConstraintSet set = loadOwned(id, CurrentUser.requireUserId());
        assertEditable(set);

        set.setName(request.getName());
        set.setDescription(request.getDescription());
        if (request.getVersion() != null) {
            set.setVersion(request.getVersion());
        }
        constraintSetMapper.updateById(set);

        List<ConstraintRuleDto> rules;
        if (request.getRules() != null) {
            assertRulesValid(request.getRules());
            constraintRuleMapper.delete(Wrappers.lambdaQuery(ConstraintRule.class)
                    .eq(ConstraintRule::getSetId, id));
            insertRules(id, request.getRules());
            rules = request.getRules();
        } else {
            rules = loadRules(List.of(id)).getOrDefault(id, List.of());
        }
        return toResponse(set, rules);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConstraintSetResponse activate(Long id) {
        ConstraintSet set = loadOwned(id, CurrentUser.requireUserId());
        if (!ConstraintStatus.DRAFT.name().equals(set.getStatus())) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "只有草稿状态的约束集可以激活，当前状态: " + set.getStatus(), Map.of("id", id, "status", set.getStatus()));
        }

        List<ConstraintRuleDto> rules = loadRules(List.of(id)).getOrDefault(id, List.of());
        // 自检：空集直接拒；存量规则可能来自更宽松的历史版本，激活前必须重新过一遍
        if (rules.isEmpty()) {
            throw new BizException(ErrorCode.CONSTRAINT_RULE_INVALID, "约束集为空，无法激活", Map.of("id", id));
        }
        assertRulesValid(rules);

        set.setStatus(ConstraintStatus.ACTIVE.name());
        constraintSetMapper.updateById(set);
        log.info("约束集 {} 已激活，规则数 {}", id, rules.size());
        return toResponse(set, rules);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConstraintSetResponse archive(Long id) {
        ConstraintSet set = loadOwned(id, CurrentUser.requireUserId());
        if (ConstraintStatus.ARCHIVED.name().equals(set.getStatus())) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT, "约束集已归档",
                    Map.of("id", id, "status", set.getStatus()));
        }
        set.setStatus(ConstraintStatus.ARCHIVED.name());
        constraintSetMapper.updateById(set);
        return toResponse(set, loadRules(List.of(id)).getOrDefault(id, List.of()));
    }

    @Override
    public RuleValidationResponse validate(List<ConstraintRuleDto> rules) {
        List<ConstraintRuleDto> target = rules == null ? List.of() : rules;
        return RuleValidationResponse.of(ConstraintRuleValidator.validateAll(target),
                target.stream().map(ConstraintRuleDto::getRuleType).toList());
    }

    @Override
    public Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ruleTypes", ruleTypeDictionary());
        meta.put("severities", List.of(RuleSeverity.HARD.name(), RuleSeverity.SOFT.name()));
        meta.put("dimensions", EvalDimension.names());
        meta.put("operators", NumericOperator.names());
        meta.put("groupLogics", List.of("AND", "OR"));
        meta.put("catalogs", FILTER_CATALOGS);
        return meta;
    }

    // ------------------------------------------------------------------ 内部

    private List<Map<String, Object>> ruleTypeDictionary() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(rule("NUMERIC_RANGE", "数值区间", "field,min,max"));
        list.add(rule("SMARTS_REQUIRED", "必须含子结构", "smarts"));
        list.add(rule("SMARTS_FORBIDDEN", "禁止含子结构", "smarts"));
        list.add(rule("SCAFFOLD_REQUIRED", "必须含骨架", "scaffoldSmiles"));
        list.add(rule("SCAFFOLD_FORBIDDEN", "禁止含骨架", "scaffoldSmiles"));
        list.add(rule("FILTER_CATALOG", "过滤器目录", "catalog,mode"));
        list.add(rule("SIMILARITY_RANGE", "相似度区间", "refSmiles,metric,min,max"));
        list.add(rule("SIMILARITY_TO_SET", "与集合相似度", "setIds,metric,max"));
        list.add(rule("RING_COUNT", "环数", "min,max,ringType"));
        list.add(rule("SUBSTITUENT_ALLOWED", "取代基白名单", "allowedSmarts"));
        list.add(rule("RULE_SET", "复用规则集", "ruleSetCode,mode"));
        return list;
    }

    private static Map<String, Object> rule(String code, String label, String requiredParams) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("requiredParams", requiredParams);
        return m;
    }

    private ConstraintSet loadOwned(Long id, Long userId) {
        ConstraintSet set = constraintSetMapper.selectById(id);
        OwnershipGuard.assertOwned(userId, set == null ? null : set.getUserId(),
                ErrorCode.CONSTRAINT_SET_NOT_FOUND, Map.of("id", id));
        return set;
    }

    private void assertEditable(ConstraintSet set) {
        if (!ConstraintStatus.DRAFT.name().equals(set.getStatus())) {
            throw new BizException(ErrorCode.CONSTRAINT_SET_NOT_EDITABLE, null,
                    Map.of("id", set.getId(), "status", set.getStatus()));
        }
    }

    private void assertRulesValid(List<ConstraintRuleDto> rules) {
        RuleValidationResponse validation = RuleValidationResponse.of(
                ConstraintRuleValidator.validateAll(rules),
                rules.stream().map(ConstraintRuleDto::getRuleType).toList());
        if (!validation.isValid()) {
            throw new BizException(ErrorCode.CONSTRAINT_RULE_INVALID, null,
                    Map.of("errors", validation.getErrors()));
        }
    }

    private void insertRules(Long setId, List<ConstraintRuleDto> rules) {
        int index = 0;
        for (ConstraintRuleDto dto : rules) {
            ConstraintRule entity = new ConstraintRule();
            entity.setSetId(setId);
            entity.setGroupKey(dto.getGroupKey() == null ? "G1" : dto.getGroupKey());
            entity.setGroupLogic(dto.getGroupLogic() == null ? "AND" : dto.getGroupLogic().toUpperCase());
            entity.setRuleType(RuleType.parse(dto.getRuleType()).name());
            entity.setSeverity(RuleSeverity.parseOrHard(dto.getSeverity()).name());
            entity.setDimension(dto.getDimension());
            entity.setOperator(dto.getOperator() == null ? null : dto.getOperator().toUpperCase());
            entity.setThresholdNum(dto.getThresholdNum());
            entity.setThresholdMax(dto.getThresholdMax());
            entity.setUnit(dto.getUnit());
            entity.setWeight(dto.getWeight() == null ? 10 : dto.getWeight());
            entity.setParamsJson(jsonCodec.write(dto.getParams() == null ? Map.of() : dto.getParams()));
            entity.setHumanReadable(dto.getHumanReadable());
            entity.setSortNo(dto.getSortNo() == null ? index : dto.getSortNo());
            entity.setEnabled(dto.getEnabled() == null || dto.getEnabled() ? 1 : 0);
            constraintRuleMapper.insert(entity);
            index++;
        }
    }

    /** 批量取规则并按 set_id 分组，避免列表接口 N+1 */
    private Map<Long, List<ConstraintRuleDto>> loadRules(List<Long> setIds) {
        if (setIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ConstraintRule> rules = constraintRuleMapper.selectList(
                Wrappers.lambdaQuery(ConstraintRule.class)
                        .in(ConstraintRule::getSetId, setIds)
                        .orderByAsc(ConstraintRule::getSetId)
                        .orderByAsc(ConstraintRule::getGroupKey)
                        .orderByAsc(ConstraintRule::getSortNo)
                        .orderByAsc(ConstraintRule::getId));
        return rules.stream()
                .collect(Collectors.groupingBy(ConstraintRule::getSetId,
                        Collectors.mapping(this::toDto, Collectors.toList())));
    }

    private ConstraintRuleDto toDto(ConstraintRule entity) {
        ConstraintRuleDto dto = new ConstraintRuleDto();
        dto.setId(entity.getId());
        dto.setGroupKey(entity.getGroupKey());
        dto.setGroupLogic(entity.getGroupLogic());
        dto.setRuleType(entity.getRuleType());
        dto.setSeverity(entity.getSeverity());
        dto.setWeight(entity.getWeight());
        dto.setParams(jsonCodec.readMap(entity.getParamsJson()));
        dto.setDimension(entity.getDimension());
        dto.setOperator(entity.getOperator());
        dto.setThresholdNum(entity.getThresholdNum());
        dto.setThresholdMax(entity.getThresholdMax());
        dto.setUnit(entity.getUnit());
        dto.setHumanReadable(entity.getHumanReadable());
        dto.setSortNo(entity.getSortNo());
        dto.setEnabled(entity.getEnabled() == null || entity.getEnabled() == 1);
        return dto;
    }

    private ConstraintSetResponse toResponse(ConstraintSet entity, List<ConstraintRuleDto> rules) {
        ConstraintSetResponse response = new ConstraintSetResponse();
        response.setId(entity.getId());
        response.setSetId(entity.getId());
        response.setSetNo(entity.getSetNo());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        response.setLogic(entity.getLogic());
        response.setStatus(entity.getStatus());
        response.setVersion(entity.getVersion());
        response.setParentId(entity.getParentId());
        response.setRules(new ArrayList<>(rules));
        return response;
    }
}
