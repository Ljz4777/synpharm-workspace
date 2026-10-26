package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ExperimentCreateRequest;
import com.synpharm.design.api.dto.ExperimentResponse;
import com.synpharm.design.application.ExperimentAppService;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.ExperimentStatus;
import com.synpharm.design.domain.model.WetExperiment;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.WetExperimentMapper;
import com.synpharm.design.support.HitJudge;
import com.synpharm.design.support.SerialNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 湿实验台账实现。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentAppServiceImpl implements ExperimentAppService {

    /** {@code wet_experiment.operator} 允许的算子（DDL 注释里只有这几个，不含 BETWEEN） */
    private static final Set<String> VALUE_OPERATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ");

    /** {@code project_target.role} 同义 */
    private static final Set<String> TARGET_ROLES = Set.of("WILD_TYPE", "MUTANT", "ANTI_TARGET", "OFF_TARGET");

    private static final String ASSAY_CATEGORY = "ASSAY";

    private final WetExperimentMapper experimentMapper;
    private final DesignProjectMapper projectMapper;
    private final DesignLibraryItemMapper libraryItemMapper;
    private final JsonCodec jsonCodec;
    private final HitJudge hitJudge;

    @Override
    public PageResult<ExperimentResponse> list(Long projectId, String assayType, String resultStatus,
                                               int page, int pageSize) {
        Long userId = CurrentUser.requireUserId();
        if (StringUtils.hasText(resultStatus) && !ExperimentStatus.isValid(resultStatus)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 resultStatus: " + resultStatus,
                    Map.of("allowedStatus", ExperimentStatus.names()));
        }
        List<Long> projectScope;
        if (projectId != null) {
            assertProjectOwned(userId, projectId);
            projectScope = List.of(projectId);
        } else {
            projectScope = ownedProjectIds(userId);
            if (projectScope.isEmpty()) {
                return PageResult.empty(page, pageSize);
            }
        }

        var result = experimentMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize),
                Wrappers.lambdaQuery(WetExperiment.class)
                        .in(WetExperiment::getProjectId, projectScope)
                        .eq(StringUtils.hasText(assayType), WetExperiment::getAssayType, assayType)
                        .eq(StringUtils.hasText(resultStatus), WetExperiment::getResultStatus, resultStatus)
                        .orderByDesc(WetExperiment::getId));
        return PageResult.of(result, this::toResponse);
    }

    @Override
    public ExperimentResponse get(Long id) {
        return toResponse(loadOwned(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExperimentResponse create(ExperimentCreateRequest request) {
        Long userId = CurrentUser.requireUserId();
        DesignProject project = assertProjectOwned(userId, request.getProjectId());
        validateEnums(request);
        assertAssayRegistered(request.getAssayType());

        WetExperiment entity = new WetExperiment();
        entity.setExperimentNo(SerialNumbers.experimentNo());
        entity.setProjectId(project.getId());
        entity.setRoundId(request.getRoundId() == null ? project.getCurrentRoundId() : request.getRoundId());
        entity.setCandidateId(request.getCandidateId());
        entity.setReportId(request.getReportId());
        entity.setAssayType(request.getAssayType());
        entity.setAssayProtocol(request.getAssayProtocol());
        entity.setTargetRole(request.getTargetRole());
        entity.setResultStatus(request.getResultStatus());
        entity.setResultNum(request.getResultNum());
        entity.setResultText(request.getResultText());
        entity.setUnit(request.getUnit());
        entity.setOperator(request.getOperator());
        entity.setPredictedValue(request.getPredictedValue());
        entity.setPredictedMetric(request.getPredictedMetric());
        entity.setLabName(request.getLabName());
        entity.setDataAssetId(request.getDataAssetId());
        entity.setOperatorId(userId);
        entity.setFeedbackToModel(0);
        applyDerived(entity, project);
        experimentMapper.insert(entity);
        log.info("实验 {} 已录入（项目 {}，判定 isHit={}）",
                entity.getId(), project.getId(), entity.getIsHit());
        return toResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExperimentResponse patch(Long id, ExperimentCreateRequest request) {
        Long userId = CurrentUser.requireUserId();
        WetExperiment entity = loadOwned(id);
        DesignProject project = assertProjectOwned(userId, request.getProjectId());
        validateEnums(request);
        assertAssayRegistered(request.getAssayType());

        // 不传 = 不变：可空字段省略时保留原值（更正结果不应该顺手清掉实验条件）
        entity.setProjectId(project.getId());
        entity.setAssayType(request.getAssayType());
        entity.setResultStatus(request.getResultStatus());
        if (request.getRoundId() != null) {
            entity.setRoundId(request.getRoundId());
        }
        if (request.getCandidateId() != null) {
            entity.setCandidateId(request.getCandidateId());
        }
        if (request.getReportId() != null) {
            entity.setReportId(request.getReportId());
        }
        if (request.getAssayProtocol() != null) {
            entity.setAssayProtocol(request.getAssayProtocol());
        }
        if (request.getTargetRole() != null) {
            entity.setTargetRole(request.getTargetRole());
        }
        if (request.getResultNum() != null) {
            entity.setResultNum(request.getResultNum());
        }
        if (request.getResultText() != null) {
            entity.setResultText(request.getResultText());
        }
        if (request.getUnit() != null) {
            entity.setUnit(request.getUnit());
        }
        if (request.getOperator() != null) {
            entity.setOperator(request.getOperator());
        }
        if (request.getPredictedValue() != null) {
            entity.setPredictedValue(request.getPredictedValue());
        }
        if (request.getPredictedMetric() != null) {
            entity.setPredictedMetric(request.getPredictedMetric());
        }
        if (request.getLabName() != null) {
            entity.setLabName(request.getLabName());
        }
        if (request.getDataAssetId() != null) {
            entity.setDataAssetId(request.getDataAssetId());
        }
        applyDerived(entity, project);
        experimentMapper.updateById(entity);
        return toResponse(entity);
    }

    // ------------------------------------------------------------------ 内部

    /** 计算 errorDelta 与 isHit（两者都不接受客户端传入） */
    private void applyDerived(WetExperiment entity, DesignProject project) {
        if (entity.getResultNum() != null && entity.getPredictedValue() != null) {
            entity.setErrorDelta(entity.getResultNum().subtract(entity.getPredictedValue()));
        } else {
            entity.setErrorDelta(null);
        }
        HitJudge.Verdict verdict = hitJudge.judge(goalMetric(project), entity.getResultNum(),
                entity.getOperator(), entity.getPredictedMetric());
        entity.setIsHit(verdict.hit() == null ? null : (verdict.hit() ? 1 : 0));
        if (verdict.hit() == null) {
            log.info("实验命中判定不可得：{}", verdict.reason());
        }
    }

    private Map<String, Object> goalMetric(DesignProject project) {
        Map<String, Object> goal = jsonCodec.readMap(project.getGoalMetricJson());
        return goal == null || goal.isEmpty() ? null : goal;
    }

    private DesignProject assertProjectOwned(Long userId, Long projectId) {
        DesignProject project = projectMapper.selectById(projectId);
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", projectId));
        return project;
    }

    private WetExperiment loadOwned(Long id) {
        WetExperiment entity = experimentMapper.selectById(id);
        OwnershipGuard.assertOwned(CurrentUser.requireUserId(),
                entity == null ? null : ownerOf(entity),
                ErrorCode.EXPERIMENT_NOT_FOUND, Map.of("id", id));
        return entity;
    }

    /**
     * 实验表的属主：{@code wet_experiment} 没有 user_id 列，属主来自所属项目。
     * {@code ownerId == null} 时返回 null，让 OwnershipGuard 走 fail-closed 分支。
     */
    private Long ownerOf(WetExperiment entity) {
        if (entity == null || entity.getProjectId() == null) {
            return null;
        }
        DesignProject project = projectMapper.selectById(entity.getProjectId());
        return project == null ? null : project.getUserId();
    }

    private List<Long> ownedProjectIds(Long userId) {
        return projectMapper.selectList(Wrappers.lambdaQuery(DesignProject.class)
                        .select(DesignProject::getId)
                        .eq(DesignProject::getUserId, userId))
                .stream()
                .map(DesignProject::getId)
                .toList();
    }

    private void validateEnums(ExperimentCreateRequest request) {
        if (!ExperimentStatus.isValid(request.getResultStatus())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 resultStatus: " + request.getResultStatus(),
                    Map.of("allowedStatus", ExperimentStatus.names()));
        }
        if (request.getTargetRole() != null && !TARGET_ROLES.contains(request.getTargetRole())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 targetRole: " + request.getTargetRole(),
                    Map.of("allowedRoles", TARGET_ROLES));
        }
        if (request.getOperator() != null && !VALUE_OPERATORS.contains(request.getOperator())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "未知的 operator: " + request.getOperator(),
                    Map.of("allowedOperators", VALUE_OPERATORS));
        }
    }

    /** 契约：assayType 必须已在知识库 {@code category=ASSAY} 注册 */
    private void assertAssayRegistered(String assayType) {
        Long count = libraryItemMapper.selectCount(Wrappers.lambdaQuery(DesignLibraryItem.class)
                .eq(DesignLibraryItem::getCategory, ASSAY_CATEGORY)
                .eq(DesignLibraryItem::getCode, assayType));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.EXPERIMENT_ASSAY_UNKNOWN,
                    "检测类型未在知识库注册: " + assayType,
                    Map.of("assayType", assayType, "category", ASSAY_CATEGORY));
        }
    }

    private ExperimentResponse toResponse(WetExperiment entity) {
        ExperimentResponse response = new ExperimentResponse();
        response.setId(entity.getId());
        response.setExperimentNo(entity.getExperimentNo());
        response.setProjectId(entity.getProjectId());
        response.setRoundId(entity.getRoundId());
        response.setCandidateId(entity.getCandidateId());
        response.setReportId(entity.getReportId());
        response.setAssayType(entity.getAssayType());
        response.setAssayProtocol(entity.getAssayProtocol());
        response.setTargetRole(entity.getTargetRole());
        response.setResultStatus(entity.getResultStatus());
        response.setResultNum(entity.getResultNum());
        response.setResultText(entity.getResultText());
        response.setUnit(entity.getUnit());
        response.setOperator(entity.getOperator());
        response.setErrorDelta(entity.getErrorDelta());
        response.setIsHit(entity.getIsHit() == null ? null : entity.getIsHit() == 1);
        response.setPredictedValue(entity.getPredictedValue());
        response.setPredictedMetric(entity.getPredictedMetric());
        response.setFeedbackToModel(entity.getFeedbackToModel() != null && entity.getFeedbackToModel() == 1);
        response.setFeedbackBatch(entity.getFeedbackBatch());
        response.setFeedbackAt(entity.getFeedbackAt());
        response.setCalibrationId(entity.getCalibrationId());
        response.setDataAssetId(entity.getDataAssetId());
        response.setLabName(entity.getLabName());
        response.setOperatorId(entity.getOperatorId());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
