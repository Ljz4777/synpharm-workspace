package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ConstraintRuleDto;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.ConstraintSetUpsertRequest;
import com.synpharm.design.api.dto.ObjectiveDto;
import com.synpharm.design.api.dto.ProjectCreatedResponse;
import com.synpharm.design.api.dto.ProjectCreateRequest;
import com.synpharm.design.api.dto.ProjectPatchRequest;
import com.synpharm.design.api.dto.ProjectResponse;
import com.synpharm.design.api.dto.ProjectTargetBindRequest;
import com.synpharm.design.api.dto.ProjectTargetResponse;
import com.synpharm.design.api.dto.RoundResponse;
import com.synpharm.design.application.ConstraintAppService;
import com.synpharm.design.application.ProjectAppService;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.ConstraintSet;
import com.synpharm.design.domain.model.ConstraintStatus;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.DesignRound;
import com.synpharm.design.domain.model.EvalDimension;
import com.synpharm.design.domain.model.ProjectStatus;
import com.synpharm.design.domain.model.ProjectTarget;
import com.synpharm.design.domain.model.RoundStatus;
import com.synpharm.design.domain.model.Target;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.ConstraintSetMapper;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.DesignRoundMapper;
import com.synpharm.design.infrastructure.persistence.ProjectTargetMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
import com.synpharm.design.support.ProjectReadinessChecker;
import com.synpharm.design.support.SerialNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目生命周期实现。
 *
 * <p>{@code READY} 是推导态：每次读/写都按「已绑靶点 且 已激活约束集」重算，
 * 并把缺口写进 {@code readyBlockers}（红线 R2：不得静默就绪）。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAppServiceImpl implements ProjectAppService {

    private static final String ROLE_DEFAULT = "WILD_TYPE";
    private static final Set<String> TARGET_ROLES =
            Set.of("WILD_TYPE", "MUTANT", "ANTI_TARGET", "OFF_TARGET");

    /** 可增删靶点的状态：一旦进入 ACTIVE，靶点集合就应该冻结 */
    private static final Set<String> TARGET_MUTABLE_STATUS =
            Set.of(ProjectStatus.DRAFT.name(), ProjectStatus.READY.name());

    private final DesignProjectMapper projectMapper;
    private final ProjectTargetMapper projectTargetMapper;
    private final TargetMapper targetMapper;
    private final DesignRoundMapper roundMapper;
    private final CandidateMoleculeMapper candidateMapper;
    private final ConstraintSetMapper constraintSetMapper;
    private final DesignLibraryItemMapper libraryItemMapper;
    private final ConstraintAppService constraintAppService;
    private final ProjectReadinessChecker readiness;
    private final JsonCodec jsonCodec;

    @Override
    public PageResult<ProjectResponse> list(String status, String keyword, int page, int pageSize) {
        Long userId = CurrentUser.requireUserId();
        ProjectStatus parsed = status == null || status.isBlank() ? null : ProjectStatus.parse(status);

        var query = Wrappers.lambdaQuery(DesignProject.class)
                .eq(DesignProject::getUserId, userId)
                .eq(parsed != null, DesignProject::getStatus, parsed == null ? null : parsed.name())
                .orderByDesc(DesignProject::getId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            query.and(w -> w.like(DesignProject::getName, kw).or().like(DesignProject::getProjectNo, kw));
        }

        var result = projectMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize), query);
        List<Long> ids = result.getRecords().stream().map(DesignProject::getId).toList();
        Map<Long, List<ProjectTargetResponse>> targets = loadTargets(ids);
        Map<Long, ProjectResponse.CandidateStats> stats = loadCandidateStats(ids);

        return PageResult.of(result, entity -> assemble(entity,
                targets.getOrDefault(entity.getId(), List.of()),
                Collections.emptyList(),
                stats.get(entity.getId())));
    }

    @Override
    public ProjectResponse get(Long id) {
        DesignProject project = loadOwned(id, CurrentUser.requireUserId());
        return assembleDetail(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectCreatedResponse create(ProjectCreateRequest request) {
        Long userId = CurrentUser.requireUserId();
        validateLibraryRef(request.getModalityId(), "MODALITY", "modalityId");
        if (request.getDiseaseId() != null) {
            validateLibraryRef(request.getDiseaseId(), "DISEASE", "diseaseId");
        }
        validateObjectives(request.getObjectives());
        validateMoaCode(request.getMoaCode());
        List<ProjectTargetBindRequest> bindings = validateBindings(request.getTargets());

        DesignProject project = new DesignProject();
        project.setProjectNo(SerialNumbers.projectNo());
        project.setUserId(userId);
        project.setName(request.getName());
        project.setGoalText(request.getGoalText());
        project.setGoalMetricJson(jsonCodec.write(resolveGoalMetric(request)));
        project.setDiseaseId(request.getDiseaseId());
        project.setModalityId(request.getModalityId());
        project.setMoaCode(request.getMoaCode());
        project.setMinCandidates(request.getMinCandidates());
        project.setQuotaGpuHours(request.getQuotaGpuHours());
        project.setQuotaWetLab(request.getQuotaWetLab());
        project.setDecisionCapacityMode(request.getDecisionCapacityMode());
        project.setDecisionCapacityValue(request.getDecisionCapacityValue());
        project.setStatus(ProjectStatus.DRAFT.name());
        projectMapper.insert(project);

        for (ProjectTargetBindRequest binding : bindings) {
            insertBinding(project.getId(), binding);
        }

        if (request.getConstraintRules() != null && !request.getConstraintRules().isEmpty()) {
            ConstraintSetResponse created = constraintAppService.create(
                    constraintSetFor(project, request.getConstraintRules()));
            // 内联给出约束规则 = 用户明确要用它，因此直接激活并置为生效；
            // 激活会跑一遍自检，非法规则在同事务内回滚
            ConstraintSetResponse activated = constraintAppService.activate(created.getId());
            project.setActiveConstraintSetId(activated.getId());
        }

        // READY 是推导态：靶点齐了且约束集生效才算就绪，缺口如实留在 readyBlockers 里
        List<String> blockers = readiness.blockers(project);
        project.setStatus(blockers.isEmpty() ? ProjectStatus.READY.name() : ProjectStatus.DRAFT.name());
        projectMapper.updateById(project);

        ProjectResponse assembled = assembleDetail(project);
        log.info("项目 {} 创建完成，状态 {}，缺口 {}", project.getId(), project.getStatus(), blockers);
        return ProjectCreatedResponse.of(assembled);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectResponse update(Long id, ProjectPatchRequest request) {
        DesignProject project = loadOwned(id, CurrentUser.requireUserId());
        if (!TARGET_MUTABLE_STATUS.contains(project.getStatus())) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_STATUS_INVALID, null,
                    Map.of("id", id, "status", project.getStatus()));
        }
        if (request.getModalityId() != null) {
            validateLibraryRef(request.getModalityId(), "MODALITY", "modalityId");
        }
        if (request.getDiseaseId() != null) {
            validateLibraryRef(request.getDiseaseId(), "DISEASE", "diseaseId");
        }
        if (request.getMoaCode() != null) {
            validateMoaCode(request.getMoaCode());
            project.setMoaCode(request.getMoaCode());
        }
        if (request.getName() != null) {
            project.setName(request.getName());
        }
        if (request.getGoalText() != null) {
            project.setGoalText(request.getGoalText());
        }
        if (request.getDiseaseId() != null) {
            project.setDiseaseId(request.getDiseaseId());
        }
        if (request.getModalityId() != null) {
            project.setModalityId(request.getModalityId());
        }
        if (request.getMinCandidates() != null) {
            project.setMinCandidates(request.getMinCandidates());
        }
        if (request.getQuotaGpuHours() != null) {
            project.setQuotaGpuHours(request.getQuotaGpuHours());
        }
        if (request.getQuotaWetLab() != null) {
            project.setQuotaWetLab(request.getQuotaWetLab());
        }
        if (StringUtils.hasText(request.getDecisionCapacityMode())) {
            project.setDecisionCapacityMode(request.getDecisionCapacityMode());
        }
        if (request.getDecisionCapacityValue() != null) {
            project.setDecisionCapacityValue(request.getDecisionCapacityValue());
        }
        projectMapper.updateById(project);
        return assembleDetail(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectTargetResponse bindTarget(Long projectId, ProjectTargetBindRequest request) {
        DesignProject project = loadOwned(projectId, CurrentUser.requireUserId());
        assertTargetMutable(project);
        if (request.getTargetId() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "targetId 不能为空");
        }
        Target target = targetMapper.selectById(request.getTargetId());
        if (target == null) {
            throw new BizException(ErrorCode.TARGET_NOT_FOUND, null, Map.of("targetId", request.getTargetId()));
        }
        if (countBinding(projectId, request.getTargetId()) > 0) {
            throw new BizException(ErrorCode.DESIGN_TARGET_DUPLICATED, null,
                    Map.of("projectId", projectId, "targetId", request.getTargetId()));
        }
        if (Boolean.TRUE.equals(request.getIsPrimary()) && findPrimaryBinding(projectId) != null) {
            throw new BizException(ErrorCode.DESIGN_PRIMARY_TARGET_EXISTS, null, Map.of("projectId", projectId));
        }
        // 老数据里可能有历史上逻辑删除留下的行（占着 uk_project_target），插入前先清掉
        projectTargetMapper.purgeBinding(projectId, request.getTargetId());

        ProjectTarget binding = insertBinding(projectId, request);
        refreshStatus(project);
        return toTargetResponse(binding, target.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindTarget(Long projectId, Long targetId) {
        DesignProject project = loadOwned(projectId, CurrentUser.requireUserId());
        assertTargetMutable(project);

        ProjectTarget binding = projectTargetMapper.selectOne(Wrappers.lambdaQuery(ProjectTarget.class)
                .eq(ProjectTarget::getProjectId, projectId)
                .eq(ProjectTarget::getTargetId, targetId)
                .last("LIMIT 1"));
        if (binding == null) {
            throw new BizException(ErrorCode.TARGET_NOT_FOUND, "该项目未绑定该靶点",
                    Map.of("projectId", projectId, "targetId", targetId));
        }
        // 必须物理删除：逻辑删除会占着 uk_project_target / uk_primary_target，导致之后
        // 重新绑定同一靶点、或解绑主靶点后再设主靶点时撞唯一键（500）
        projectTargetMapper.hardDeleteById(binding.getId());
        // 主靶点被解绑后不自动提升其它靶点：谁是主靶点是用户决策，不是系统的猜测
        refreshStatus(project);
    }

    @Override
    public ConstraintSetResponse getProjectConstraints(Long projectId) {
        DesignProject project = loadOwned(projectId, CurrentUser.requireUserId());
        if (project.getActiveConstraintSetId() == null) {
            return null;
        }
        return constraintAppService.get(project.getActiveConstraintSetId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConstraintSetResponse bindConstraintSet(Long projectId, Long constraintSetId, boolean activate) {
        DesignProject project = loadOwned(projectId, CurrentUser.requireUserId());
        if (!TARGET_MUTABLE_STATUS.contains(project.getStatus())) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_STATUS_INVALID, null,
                    Map.of("id", projectId, "status", project.getStatus()));
        }
        ConstraintSet set = constraintSetMapper.selectById(constraintSetId);
        OwnershipGuard.assertOwned(CurrentUser.requireUserId(), set == null ? null : set.getUserId(),
                ErrorCode.CONSTRAINT_SET_NOT_FOUND, Map.of("constraintSetId", constraintSetId));

        ConstraintSetResponse response;
        if (ConstraintStatus.DRAFT.name().equals(set.getStatus()) && activate) {
            // 激活会跑一遍规则自检，失败则同事务回滚，不会留下「绑了个坏约束集」的项目
            response = constraintAppService.activate(constraintSetId);
        } else {
            if (!ConstraintStatus.ACTIVE.name().equals(set.getStatus())) {
                throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                        "只有已激活的约束集可以绑定为项目生效约束，当前状态: " + set.getStatus()
                                + "；如需自动激活请传 activate=true",
                        Map.of("constraintSetId", constraintSetId, "status", set.getStatus()));
            }
            response = constraintAppService.get(constraintSetId);
        }

        project.setActiveConstraintSetId(constraintSetId);
        projectMapper.updateById(project);
        refreshStatus(project);
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectResponse close(Long id, String summary) {
        DesignProject project = loadOwned(id, CurrentUser.requireUserId());
        if (!ProjectStatus.ACTIVE.name().equals(project.getStatus())
                && !ProjectStatus.READY.name().equals(project.getStatus())) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_STATUS_INVALID,
                    "只有进行中或已就绪的项目可以结项，当前状态: " + project.getStatus(),
                    Map.of("id", id, "status", project.getStatus()));
        }
        // 留着未关闭的轮次就结项，会让「轮次」这张表出现永远关不掉的悬空记录
        Long openRounds = roundMapper.selectCount(Wrappers.lambdaQuery(DesignRound.class)
                .eq(DesignRound::getProjectId, id)
                .ne(DesignRound::getStatus, RoundStatus.CLOSED.name()));
        if (openRounds != null && openRounds > 0) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "存在未关闭的轮次，请先关闭轮次再结项",
                    Map.of("id", id, "openRounds", openRounds));
        }

        project.setSummary(summary);
        project.setStatus(ProjectStatus.COMPLETED.name());
        project.setClosedAt(LocalDateTime.now());
        projectMapper.updateById(project);
        log.info("项目 {} 已结项", id);
        return assembleDetail(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectResponse archive(Long id) {
        DesignProject project = loadOwned(id, CurrentUser.requireUserId());
        if (!ProjectStatus.COMPLETED.name().equals(project.getStatus())
                && !ProjectStatus.CANCELLED.name().equals(project.getStatus())) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_STATUS_INVALID,
                    "只有已结项或已取消的项目可以归档，当前状态: " + project.getStatus(),
                    Map.of("id", id, "status", project.getStatus()));
        }
        project.setStatus(ProjectStatus.ARCHIVED.name());
        projectMapper.updateById(project);
        log.info("项目 {} 已归档", id);
        return assembleDetail(project);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 重算推导态并写回。
     *
     * <p>只在 DRAFT / READY 之间流转；进入 ACTIVE 之后状态由轮次与报告驱动，
     * 这里只负责把缺口暴露出来，不擅自回退。
     */
    private void refreshStatus(DesignProject project) {
        List<String> blockers = readiness.blockers(project);
        if (TARGET_MUTABLE_STATUS.contains(project.getStatus())) {
            String next = blockers.isEmpty() ? ProjectStatus.READY.name() : ProjectStatus.DRAFT.name();
            if (!next.equals(project.getStatus())) {
                project.setStatus(next);
                projectMapper.updateById(project);
            }
        }
    }

    private void assertTargetMutable(DesignProject project) {
        if (!TARGET_MUTABLE_STATUS.contains(project.getStatus())) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_STATUS_INVALID,
                    "项目已进入 " + project.getStatus() + "，靶点集合已冻结",
                    Map.of("id", project.getId(), "status", project.getStatus()));
        }
    }

    private DesignProject loadOwned(Long id, Long userId) {
        DesignProject project = projectMapper.selectById(id);
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("id", id));
        return project;
    }

    private void validateLibraryRef(Long id, String category, String field) {
        DesignLibraryItem item = libraryItemMapper.selectById(id);
        if (item == null || !category.equals(item.getCategory())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    field + " 必须是知识库 " + category + " 分类下的有效项",
                    Map.of(field, id, "expectedCategory", category));
        }
    }

    private void validateObjectives(List<ObjectiveDto> objectives) {
        if (objectives == null) {
            return;
        }
        for (ObjectiveDto objective : objectives) {
            if (!EvalDimension.isValid(objective.getDimension())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "未知的优化目标维度: " + objective.getDimension(),
                        Map.of("allowedDimensions", EvalDimension.names()));
            }
        }
    }

    /**
     * 校验作用机制码。
     *
     * <p>只接受字典里存在的 {@code MOA} 分类项：机制是生成策略选型的第一依据，
     * 自由文本填错不会被任何环节发现，只会让策略模板悄悄选错。
     */
    private void validateMoaCode(String moaCode) {
        if (!StringUtils.hasText(moaCode)) {
            return;
        }
        DesignLibraryItem item = libraryItemMapper.selectOne(Wrappers.lambdaQuery(DesignLibraryItem.class)
                .eq(DesignLibraryItem::getCategory, "MOA")
                .eq(DesignLibraryItem::getCode, moaCode)
                .last("LIMIT 1"));
        if (item == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "moaCode 必须是知识库 MOA 分类下的有效项: " + moaCode,
                    Map.of("moaCode", moaCode));
        }
    }

    /** 校验绑定列表：至少一条、恰好一个主靶点、role 合法、targetId 不重复 */
    private List<ProjectTargetBindRequest> validateBindings(List<ProjectTargetBindRequest> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "targets 至少需要一条");
        }
        long primaryCount = targets.stream().filter(t -> Boolean.TRUE.equals(t.getIsPrimary())).count();
        if (primaryCount != 1) {
            throw new BizException(primaryCount == 0 ? ErrorCode.VALIDATION_FAILED : ErrorCode.DESIGN_PRIMARY_TARGET_EXISTS,
                    "targets 必须恰好有一条 isPrimary=true，当前 " + primaryCount + " 条",
                    Map.of("primaryCount", primaryCount));
        }
        Set<Long> seen = new java.util.HashSet<>();
        for (ProjectTargetBindRequest binding : targets) {
            if (binding.getTargetId() == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "targetId 不能为空");
            }
            if (!seen.add(binding.getTargetId())) {
                throw new BizException(ErrorCode.DESIGN_TARGET_DUPLICATED, null,
                        Map.of("targetId", binding.getTargetId()));
            }
            if (StringUtils.hasText(binding.getRole()) && !TARGET_ROLES.contains(binding.getRole().toUpperCase())) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "未知的 target role: " + binding.getRole(), Map.of("allowedRoles", TARGET_ROLES));
            }
            if (targetMapper.selectById(binding.getTargetId()) == null) {
                throw new BizException(ErrorCode.TARGET_NOT_FOUND, null, Map.of("targetId", binding.getTargetId()));
            }
        }
        return targets;
    }

    private Map<String, Object> resolveGoalMetric(ProjectCreateRequest request) {
        if (request.getGoalMetric() != null && !request.getGoalMetric().isEmpty()) {
            return request.getGoalMetric();
        }
        if (request.getObjectives() == null || request.getObjectives().isEmpty()) {
            return null;
        }
        Map<String, Object> metric = new LinkedHashMap<>();
        for (ObjectiveDto objective : request.getObjectives()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("operator", objective.getOperator());
            entry.put("threshold", objective.getThreshold());
            entry.put("unit", objective.getUnit());
            entry.put("weight", objective.getWeight());
            metric.put(objective.getDimension(), entry);
        }
        return metric;
    }

    private ConstraintSetUpsertRequest constraintSetFor(DesignProject project, List<ConstraintRuleDto> rules) {
        ConstraintSetUpsertRequest request = new ConstraintSetUpsertRequest();
        request.setName(project.getName() + " · 内联约束");
        request.setDescription("随项目 " + project.getProjectNo() + " 创建时内联");
        request.setRules(rules);
        return request;
    }

    private ProjectTarget insertBinding(Long projectId, ProjectTargetBindRequest request) {
        ProjectTarget binding = new ProjectTarget();
        binding.setProjectId(projectId);
        binding.setTargetId(request.getTargetId());
        binding.setIsPrimary(Boolean.TRUE.equals(request.getIsPrimary()) ? 1 : 0);
        String role = StringUtils.hasText(request.getRole()) ? request.getRole().toUpperCase() : ROLE_DEFAULT;
        binding.setRole(role);
        binding.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
        projectTargetMapper.insert(binding);
        return binding;
    }

    private long countBinding(Long projectId, Long targetId) {
        Long count = projectTargetMapper.selectCount(Wrappers.lambdaQuery(ProjectTarget.class)
                .eq(ProjectTarget::getProjectId, projectId)
                .eq(ProjectTarget::getTargetId, targetId));
        return count == null ? 0 : count;
    }

    private ProjectTarget findPrimaryBinding(Long projectId) {
        return projectTargetMapper.selectOne(Wrappers.lambdaQuery(ProjectTarget.class)
                .eq(ProjectTarget::getProjectId, projectId)
                .eq(ProjectTarget::getIsPrimary, 1)
                .last("LIMIT 1"));
    }

    private ProjectResponse assembleDetail(DesignProject project) {
        List<Long> ids = List.of(project.getId());
        return assemble(project,
                loadTargets(ids).getOrDefault(project.getId(), List.of()),
                loadRounds(project.getId()),
                loadCandidateStats(ids).get(project.getId()));
    }

    private ProjectResponse assemble(DesignProject project, List<ProjectTargetResponse> targets,
                                     List<RoundResponse> rounds,
                                     ProjectResponse.CandidateStats stats) {
        ProjectResponse response = new ProjectResponse();
        response.setId(project.getId());
        response.setProjectNo(project.getProjectNo());
        response.setName(project.getName());
        response.setGoalText(project.getGoalText());
        response.setGoalMetric(jsonCodec.readMap(project.getGoalMetricJson()));
        response.setDiseaseId(project.getDiseaseId());
        response.setModalityId(project.getModalityId());
        response.setMoaCode(project.getMoaCode());
        response.setMinCandidates(project.getMinCandidates());
        response.setQuotaGpuHours(project.getQuotaGpuHours());
        response.setQuotaGpuUsed(project.getQuotaGpuUsed());
        response.setQuotaWetLab(project.getQuotaWetLab());
        response.setQuotaWetUsed(project.getQuotaWetUsed());
        response.setDecisionCapacityMode(project.getDecisionCapacityMode());
        response.setDecisionCapacityValue(project.getDecisionCapacityValue());
        response.setCurrentBaselineId(project.getCurrentBaselineId());
        response.setProjectVersion(project.getProjectVersion());
        response.setActiveConstraintSetId(project.getActiveConstraintSetId());
        response.setCurrentRoundId(project.getCurrentRoundId());
        response.setStatus(project.getStatus());
        response.setSummary(project.getSummary());
        response.setStartedAt(project.getStartedAt());
        response.setClosedAt(project.getClosedAt());
        response.setCreatedAt(project.getCreatedAt());
        response.setTargets(new ArrayList<>(targets));
        response.setRounds(new ArrayList<>(rounds));
        response.setReadyBlockers(readiness.blockers(project));
        if (stats != null) {
            response.setCandidateStats(stats);
        }
        return response;
    }

    private List<RoundResponse> loadRounds(Long projectId) {
        List<DesignRound> rounds = roundMapper.selectList(Wrappers.lambdaQuery(DesignRound.class)
                .eq(DesignRound::getProjectId, projectId)
                .orderByAsc(DesignRound::getRoundNo));
        List<RoundResponse> result = new ArrayList<>();
        for (DesignRound round : rounds) {
            RoundResponse dto = new RoundResponse();
            dto.setId(round.getId());
            dto.setProjectId(round.getProjectId());
            dto.setRoundNo(round.getRoundNo());
            dto.setStrategyNote(round.getStrategyNote());
            dto.setStatus(round.getStatus());
            dto.setConclusionText(round.getConclusionText());
            dto.setMetrics(jsonCodec.readMap(round.getMetricsJson()));
            dto.setOpenedAt(round.getOpenedAt());
            dto.setClosedAt(round.getClosedAt());
            result.add(dto);
        }
        return result;
    }

    private Map<Long, List<ProjectTargetResponse>> loadTargets(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProjectTarget> bindings = projectTargetMapper.selectList(Wrappers.lambdaQuery(ProjectTarget.class)
                .in(ProjectTarget::getProjectId, projectIds)
                .orderByAsc(ProjectTarget::getProjectId)
                .orderByAsc(ProjectTarget::getSortNo));
        Map<Long, String> names = loadTargetNames(bindings.stream().map(ProjectTarget::getTargetId).toList());

        Map<Long, List<ProjectTargetResponse>> grouped = new HashMap<>();
        for (ProjectTarget binding : bindings) {
            grouped.computeIfAbsent(binding.getProjectId(), k -> new ArrayList<>())
                    .add(toTargetResponse(binding, names.get(binding.getTargetId())));
        }
        return grouped;
    }

    private Map<Long, String> loadTargetNames(List<Long> targetIds) {
        if (targetIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return targetMapper.selectList(Wrappers.lambdaQuery(Target.class).in(Target::getId, targetIds))
                .stream().collect(Collectors.toMap(Target::getId, Target::getName, (a, b) -> a));
    }

    private ProjectTargetResponse toTargetResponse(ProjectTarget binding, String targetName) {
        ProjectTargetResponse response = new ProjectTargetResponse();
        response.setId(binding.getId());
        response.setTargetId(binding.getTargetId());
        response.setTargetName(targetName);
        response.setIsPrimary(Integer.valueOf(1).equals(binding.getIsPrimary()));
        response.setRole(binding.getRole());
        response.setSortNo(binding.getSortNo());
        return response;
    }

    /** 候选按 review_status 归并统计；TOP/SELECTED 计入 top，QUALIFIED 及以上计入 qualified */
    private Map<Long, ProjectResponse.CandidateStats> loadCandidateStats(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = candidateMapper.selectMaps(new QueryWrapper<CandidateMolecule>()
                .select("project_id AS projectId",
                        "COUNT(*) AS totalCnt",
                        "SUM(CASE WHEN review_status IN ('QUALIFIED','TOP','SELECTED') THEN 1 ELSE 0 END) AS qualifiedCnt",
                        "SUM(CASE WHEN review_status IN ('TOP','SELECTED') THEN 1 ELSE 0 END) AS topCnt")
                .in("project_id", projectIds)
                .groupBy("project_id"));

        Map<Long, ProjectResponse.CandidateStats> stats = new HashMap<>();
        for (Map<String, Object> row : rows) {
            ProjectResponse.CandidateStats item = new ProjectResponse.CandidateStats();
            item.setTotal(asLong(row.get("totalCnt")));
            item.setQualified(asLong(row.get("qualifiedCnt")));
            item.setTop(asLong(row.get("topCnt")));
            stats.put(asLong(row.get("projectId")), item);
        }
        return stats;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
