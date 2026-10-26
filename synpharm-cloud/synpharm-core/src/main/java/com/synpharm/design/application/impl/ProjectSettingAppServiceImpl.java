package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.ProjectMetricResponse;
import com.synpharm.design.api.dto.ProjectMetricUpsertRequest;
import com.synpharm.design.api.dto.ProjectRiskResponse;
import com.synpharm.design.api.dto.ProjectRiskUpsertRequest;
import com.synpharm.design.application.ProjectSettingAppService;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.MetricDefinition;
import com.synpharm.design.domain.model.NumericOperator;
import com.synpharm.design.domain.model.ProjectMetric;
import com.synpharm.design.domain.model.ProjectRiskItem;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.MetricDefinitionMapper;
import com.synpharm.design.infrastructure.persistence.ProjectMetricMapper;
import com.synpharm.design.infrastructure.persistence.ProjectRiskItemMapper;
import com.synpharm.design.support.BoundaryJudge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目级设置用例实现（指标 / 风险关注点）。
 *
 * <p>这两块都在基线八块里，但都不是「项目创建时必填」—— 用户可以先建项目再慢慢补。
 * 所以它们被拆成独立的 CRUD，而不是塞进项目创建。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectSettingAppServiceImpl implements ProjectSettingAppService {

    private static final List<String> RISK_SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> BOUNDARY_POLICIES = Set.of(
            BoundaryJudge.STRICT, BoundaryJudge.WITHIN_TOLERANCE, BoundaryJudge.ESCALATE_TO_SOFT);

    private final DesignProjectMapper designProjectMapper;
    private final ProjectMetricMapper projectMetricMapper;
    private final ProjectRiskItemMapper projectRiskItemMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DesignLibraryItemMapper libraryItemMapper;
    private final JsonCodec jsonCodec;

    // ------------------------------------------------------------------ 指标

    @Override
    public List<ProjectMetricResponse> listMetrics(Long projectId) {
        loadOwnedProject(projectId);
        Map<String, MetricDefinition> defs = loadDefinitions();
        return projectMetricMapper.selectList(
                        Wrappers.lambdaQuery(ProjectMetric.class)
                                .eq(ProjectMetric::getProjectId, projectId)
                                .orderByAsc(ProjectMetric::getPriority)
                                .orderByAsc(ProjectMetric::getId))
                .stream()
                .map(m -> toResponse(m, defs.get(m.getMetricCode())))
                .collect(Collectors.toList());
    }

    private Map<String, MetricDefinition> loadDefinitions() {
        return metricDefinitionMapper.selectList(
                        Wrappers.lambdaQuery(MetricDefinition.class).eq(MetricDefinition::getEnabled, 1))
                .stream()
                .collect(Collectors.toMap(MetricDefinition::getMetricCode, d -> d, (a, b) -> a));
    }

    @Override
    @Transactional
    public ProjectMetricResponse addMetric(Long projectId, ProjectMetricUpsertRequest request) {
        loadOwnedProject(projectId);
        MetricDefinition def = requireDefinition(request.getMetricCode());

        boolean exists = projectMetricMapper.selectCount(
                Wrappers.lambdaQuery(ProjectMetric.class)
                        .eq(ProjectMetric::getProjectId, projectId)
                        .eq(ProjectMetric::getMetricCode, def.getMetricCode())) > 0;
        if (exists) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "指标「" + def.getMetricCode() + "」已存在，请直接修改");
        }

        ProjectMetric entity = new ProjectMetric();
        entity.setProjectId(projectId);
        entity.setMetricCode(def.getMetricCode());
        entity.setEnabled(1);
        applyRequest(entity, request, def);
        projectMetricMapper.insert(entity);
        return toResponse(entity, def);
    }

    @Override
    @Transactional
    public ProjectMetricResponse updateMetric(Long projectId, Long metricId, ProjectMetricUpsertRequest request) {
        loadOwnedProject(projectId);
        ProjectMetric entity = projectMetricMapper.selectById(metricId);
        if (entity == null || !projectId.equals(entity.getProjectId())) {
            throw new BizException(ErrorCode.PROJECT_METRIC_NOT_FOUND, null, Map.of("metricId", metricId));
        }
        MetricDefinition def;
        if (StringUtils.hasText(request.getMetricCode())
                && !request.getMetricCode().equals(entity.getMetricCode())) {
            def = requireDefinition(request.getMetricCode());
            boolean clash = projectMetricMapper.selectCount(
                    Wrappers.lambdaQuery(ProjectMetric.class)
                            .eq(ProjectMetric::getProjectId, projectId)
                            .eq(ProjectMetric::getMetricCode, def.getMetricCode())
                            .ne(ProjectMetric::getId, metricId)) > 0;
            if (clash) {
                throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                        "指标「" + def.getMetricCode() + "」已存在");
            }
            entity.setMetricCode(def.getMetricCode());
        } else {
            def = findDefinition(entity.getMetricCode());
        }
        applyRequest(entity, request, def);
        projectMetricMapper.updateById(entity);
        return toResponse(entity, def);
    }

    @Override
    @Transactional
    public void deleteMetric(Long projectId, Long metricId) {
        loadOwnedProject(projectId);
        ProjectMetric entity = projectMetricMapper.selectById(metricId);
        if (entity == null || !projectId.equals(entity.getProjectId())) {
            throw new BizException(ErrorCode.PROJECT_METRIC_NOT_FOUND, null, Map.of("metricId", metricId));
        }
        projectMetricMapper.deleteById(metricId);
    }

    @Override
    @Transactional
    public List<ProjectMetricResponse> initMetricsFromModality(Long projectId) {
        DesignProject project = loadOwnedProject(projectId);
        if (project.getModalityId() == null) {
            throw new BizException(ErrorCode.MODALITY_MISMATCH, "项目尚未选择分子形式，无法按形式补齐指标");
        }
        DesignLibraryItem modality = libraryItemMapper.selectById(project.getModalityId());
        if (modality == null) {
            throw new BizException(ErrorCode.MODALITY_MISMATCH, "分子形式字典项已不存在");
        }

        Set<String> existing = projectMetricMapper.selectList(
                        Wrappers.lambdaQuery(ProjectMetric.class)
                                .eq(ProjectMetric::getProjectId, projectId))
                .stream()
                .map(ProjectMetric::getMetricCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<MetricDefinition> candidates = metricDefinitionMapper.selectList(
                        Wrappers.lambdaQuery(MetricDefinition.class)
                                .eq(MetricDefinition::getEnabled, 1)
                                .orderByAsc(MetricDefinition::getSortNo))
                .stream()
                // weight=0 的是「参考指标」，字典用它表示不建议默认纳入
                .filter(d -> d.getWeight() != null && d.getWeight().signum() > 0)
                .filter(d -> appliesTo(d.getAppliesTo(), modality.getCode()))
                .collect(Collectors.toList());

        int added = 0;
        int priority = 100;
        for (MetricDefinition def : candidates) {
            if (existing.contains(def.getMetricCode())) {
                continue;
            }
            ProjectMetric entity = new ProjectMetric();
            entity.setProjectId(projectId);
            entity.setMetricCode(def.getMetricCode());
            entity.setDimension(def.getDimension());
            entity.setWeight(def.getWeight());
            entity.setOperator("GTE");
            entity.setBoundaryPolicy(BoundaryJudge.STRICT);
            entity.setPriority(priority);
            entity.setEnabled(1);
            projectMetricMapper.insert(entity);
            existing.add(def.getMetricCode());
            priority += 10;
            added++;
        }
        log.info("按分子形式补齐指标 projectId={} modality={} 新增={}", projectId, modality.getCode(), added);
        return listMetrics(projectId);
    }

    // ------------------------------------------------------------------ 风险

    @Override
    public List<ProjectRiskResponse> listRisks(Long projectId) {
        loadOwnedProject(projectId);
        return projectRiskItemMapper.selectList(
                        Wrappers.lambdaQuery(ProjectRiskItem.class)
                                .eq(ProjectRiskItem::getProjectId, projectId)
                                .orderByAsc(ProjectRiskItem::getId))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProjectRiskResponse addRisk(Long projectId, ProjectRiskUpsertRequest request) {
        loadOwnedProject(projectId);
        validateRisk(request);
        if (StringUtils.hasText(request.getRiskCode())) {
            boolean exists = projectRiskItemMapper.selectCount(
                    Wrappers.lambdaQuery(ProjectRiskItem.class)
                            .eq(ProjectRiskItem::getProjectId, projectId)
                            .eq(ProjectRiskItem::getRiskCode, request.getRiskCode())) > 0;
            if (exists) {
                throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                        "风险点「" + request.getRiskCode() + "」已存在，请直接修改");
            }
        }
        ProjectRiskItem entity = new ProjectRiskItem();
        entity.setProjectId(projectId);
        entity.setMustVerify(0);
        applyRequest(entity, request);
        projectRiskItemMapper.insert(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public ProjectRiskResponse updateRisk(Long projectId, Long riskId, ProjectRiskUpsertRequest request) {
        loadOwnedProject(projectId);
        ProjectRiskItem entity = projectRiskItemMapper.selectById(riskId);
        if (entity == null || !projectId.equals(entity.getProjectId())) {
            throw new BizException(ErrorCode.PROJECT_RISK_NOT_FOUND, null, Map.of("riskId", riskId));
        }
        validateRisk(request);
        applyRequest(entity, request);
        projectRiskItemMapper.updateById(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void deleteRisk(Long projectId, Long riskId) {
        loadOwnedProject(projectId);
        ProjectRiskItem entity = projectRiskItemMapper.selectById(riskId);
        if (entity == null || !projectId.equals(entity.getProjectId())) {
            throw new BizException(ErrorCode.PROJECT_RISK_NOT_FOUND, null, Map.of("riskId", riskId));
        }
        projectRiskItemMapper.deleteById(riskId);
    }

    // ------------------------------------------------------------------ 内部

    private DesignProject loadOwnedProject(Long projectId) {
        Long userId = CurrentUser.requireUserId();
        DesignProject project = designProjectMapper.selectById(projectId);
        OwnershipGuard.assertOwned(userId,
                project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", projectId));
        return project;
    }

    private MetricDefinition requireDefinition(String metricCode) {
        if (!StringUtils.hasText(metricCode)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "指标码不能为空");
        }
        MetricDefinition def = findDefinition(metricCode.trim());
        if (def == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "指标「" + metricCode + "」不在指标字典中或已停用");
        }
        return def;
    }

    private MetricDefinition findDefinition(String metricCode) {
        if (!StringUtils.hasText(metricCode)) {
            return null;
        }
        return metricDefinitionMapper.selectOne(
                Wrappers.lambdaQuery(MetricDefinition.class)
                        .eq(MetricDefinition::getMetricCode, metricCode.trim())
                        .eq(MetricDefinition::getEnabled, 1)
                        .last("LIMIT 1"));
    }

    /** {@code applies_to} 是 JSON 数组字符串；空表示对所有形式适用。 */
    private boolean appliesTo(String appliesToJson, String modalityCode) {
        if (!StringUtils.hasText(appliesToJson)) {
            return true;
        }
        if (!StringUtils.hasText(modalityCode)) {
            return true;
        }
        return appliesToJson.contains("\"" + modalityCode + "\"");
    }

    private void applyRequest(ProjectMetric entity, ProjectMetricUpsertRequest req, MetricDefinition def) {
        if (StringUtils.hasText(req.getDimension())) {
            entity.setDimension(req.getDimension().trim());
        } else if (entity.getDimension() == null && def != null) {
            entity.setDimension(def.getDimension());
        }

        if (req.getWeight() != null) {
            if (req.getWeight().signum() < 0) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "指标权重不能为负数");
            }
            entity.setWeight(req.getWeight());
        } else if (entity.getWeight() == null) {
            entity.setWeight(def != null && def.getWeight() != null ? def.getWeight() : BigDecimal.ONE);
        }

        entity.setTargetValue(req.getTargetValue() != null ? req.getTargetValue() : entity.getTargetValue());
        entity.setThreshold(req.getThreshold() != null ? req.getThreshold() : entity.getThreshold());

        if (StringUtils.hasText(req.getOperator())) {
            NumericOperator op = NumericOperator.parse(req.getOperator());
            if (op == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "不支持的比较算子「" + req.getOperator() + "」，可用：" + NumericOperator.names());
            }
            if (op == NumericOperator.BETWEEN) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "指标不支持 BETWEEN（只有一个阈值列），请改用单侧算子");
            }
            entity.setOperator(op.name());
        } else if (entity.getOperator() == null) {
            // 方向由字典的 better 决定：HIGHER 的指标「越大越好」，所以达标是 ≥
            entity.setOperator(def != null && "LOWER".equalsIgnoreCase(def.getBetter()) ? "LTE" : "GTE");
        }

        if (req.getTolerance() != null) {
            if (req.getTolerance().signum() < 0) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "容差不能为负数");
            }
            entity.setTolerance(req.getTolerance());
        }

        if (StringUtils.hasText(req.getBoundaryPolicy())) {
            String policy = req.getBoundaryPolicy().trim().toUpperCase();
            if (!BOUNDARY_POLICIES.contains(policy)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "不支持的边界策略「" + req.getBoundaryPolicy() + "」，可用：" + BOUNDARY_POLICIES);
            }
            entity.setBoundaryPolicy(policy);
        } else if (entity.getBoundaryPolicy() == null) {
            entity.setBoundaryPolicy(BoundaryJudge.STRICT);
        }

        entity.setPriority(req.getPriority() != null ? req.getPriority()
                : (entity.getPriority() == null ? 100 : entity.getPriority()));
        if (req.getEnabled() != null) {
            entity.setEnabled(Boolean.TRUE.equals(req.getEnabled()) ? 1 : 0);
        } else if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
    }

    private void validateRisk(ProjectRiskUpsertRequest req) {
        if (StringUtils.hasText(req.getSeverity())) {
            String sev = req.getSeverity().trim().toUpperCase();
            if (!RISK_SEVERITIES.contains(sev)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "不支持的风险等级「" + req.getSeverity() + "」，可用：" + RISK_SEVERITIES);
            }
        }
    }

    private void applyRequest(ProjectRiskItem entity, ProjectRiskUpsertRequest req) {
        if (req.getRiskCode() != null) {
            entity.setRiskCode(req.getRiskCode().trim());
        }
        entity.setDimension(req.getDimension() != null ? req.getDimension() : entity.getDimension());
        entity.setDescription(req.getDescription() != null ? req.getDescription() : entity.getDescription());
        if (StringUtils.hasText(req.getSeverity())) {
            entity.setSeverity(req.getSeverity().trim().toUpperCase());
        } else if (entity.getSeverity() == null) {
            entity.setSeverity("MEDIUM");
        }
        if (req.getMustVerify() != null) {
            entity.setMustVerify(Boolean.TRUE.equals(req.getMustVerify()) ? 1 : 0);
        } else if (entity.getMustVerify() == null) {
            entity.setMustVerify(0);
        }
        if (StringUtils.hasText(req.getSource())) {
            entity.setSource(req.getSource().trim().toUpperCase());
        } else if (entity.getSource() == null) {
            entity.setSource("MANUAL");
        }
    }

    private ProjectMetricResponse toResponse(ProjectMetric m, MetricDefinition known) {
        MetricDefinition def = known != null ? known : findDefinition(m.getMetricCode());
        ProjectMetricResponse r = new ProjectMetricResponse();
        r.setId(m.getId());
        r.setProjectId(m.getProjectId());
        r.setMetricCode(m.getMetricCode());
        r.setMetricName(def == null ? null : def.getMetricName());
        r.setDimension(m.getDimension() != null ? m.getDimension() : (def == null ? null : def.getDimension()));
        r.setUnit(def == null ? null : def.getUnit());
        r.setBetter(def == null ? null : def.getBetter());
        r.setWeight(m.getWeight());
        r.setTargetValue(m.getTargetValue());
        r.setThreshold(m.getThreshold());
        r.setOperator(m.getOperator());
        r.setTolerance(m.getTolerance());
        r.setBoundaryPolicy(m.getBoundaryPolicy());
        r.setPriority(m.getPriority());
        r.setEnabled(Integer.valueOf(1).equals(m.getEnabled()));
        r.setBoundaryText(boundaryText(m, def));
        return r;
    }

    private String boundaryText(ProjectMetric m, MetricDefinition def) {
        if (m.getThreshold() == null) {
            return "未设置阈值";
        }
        NumericOperator op = NumericOperator.parse(m.getOperator());
        String symbol = op == null ? "?" : switch (op) {
            case GT -> ">";
            case GTE -> "≥";
            case LT -> "<";
            case LTE -> "≤";
            case EQ -> "=";
            case BETWEEN -> "∈";
        };
        StringBuilder sb = new StringBuilder();
        sb.append(symbol).append(' ').append(m.getThreshold().stripTrailingZeros().toPlainString());
        if (def != null && StringUtils.hasText(def.getUnit())) {
            sb.append(' ').append(def.getUnit());
        }
        if (m.getTolerance() != null && m.getTolerance().signum() > 0) {
            sb.append("（容差 ").append(m.getTolerance().stripTrailingZeros().toPlainString());
            if (BoundaryJudge.WITHIN_TOLERANCE.equals(m.getBoundaryPolicy())) {
                sb.append("，容差内需人工确认");
            }
            sb.append('）');
        } else if (BoundaryJudge.ESCALATE_TO_SOFT.equals(m.getBoundaryPolicy())) {
            sb.append("（越界转软约束扣分）");
        }
        return sb.toString();
    }

    private ProjectRiskResponse toResponse(ProjectRiskItem e) {
        ProjectRiskResponse r = new ProjectRiskResponse();
        r.setId(e.getId());
        r.setProjectId(e.getProjectId());
        r.setRiskCode(e.getRiskCode());
        r.setDimension(e.getDimension());
        r.setDescription(e.getDescription());
        r.setSeverity(e.getSeverity());
        r.setMustVerify(Integer.valueOf(1).equals(e.getMustVerify()));
        r.setSource(e.getSource());
        return r;
    }
}
