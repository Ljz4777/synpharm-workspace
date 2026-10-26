package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.ProjectMetricResponse;
import com.synpharm.design.api.dto.ProjectMetricUpsertRequest;
import com.synpharm.design.api.dto.ProjectRiskResponse;
import com.synpharm.design.api.dto.ProjectRiskUpsertRequest;
import com.synpharm.design.application.ProjectSettingAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设计域 · 项目设置（基线块6 指标 / 块8 风险关注点）。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/projects")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 项目设置", description = "评估指标与风险关注点的增删改查")
public class ProjectSettingController {

    private final ProjectSettingAppService projectSettingAppService;

    @GetMapping("/{projectId}/metrics")
    @Operation(summary = "指标列表", description = """
            含字典回填的名称/单位/好坏方向，以及人类可读判定式 boundaryText。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、UNAUTHORIZED(40100)。""")
    public Result<List<ProjectMetricResponse>> listMetrics(@PathVariable Long projectId) {
        return Result.success(projectSettingAppService.listMetrics(projectId));
    }

    @PostMapping("/{projectId}/metrics")
    @Operation(summary = "新增指标", description = """
            指标码必须在指标字典中存在且启用；同一项目内不可重复。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、VALIDATION_FAILED(40000)、
            BUSINESS_CONFLICT(40901)、UNAUTHORIZED(40100)。""")
    public Result<ProjectMetricResponse> addMetric(@PathVariable Long projectId,
                                                    @Valid @RequestBody ProjectMetricUpsertRequest request) {
        return Result.success(projectSettingAppService.addMetric(projectId, request));
    }

    @PutMapping("/{projectId}/metrics/{metricId}")
    @Operation(summary = "修改指标", description = """
            只更新请求里给出的字段；未给的保持原值。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、PROJECT_METRIC_NOT_FOUND(40456)、
            VALIDATION_FAILED(40000)、BUSINESS_CONFLICT(40901)、UNAUTHORIZED(40100)。""")
    public Result<ProjectMetricResponse> updateMetric(@PathVariable Long projectId,
                                                      @PathVariable Long metricId,
                                                      @Valid @RequestBody ProjectMetricUpsertRequest request) {
        return Result.success(projectSettingAppService.updateMetric(projectId, metricId, request));
    }

    @DeleteMapping("/{projectId}/metrics/{metricId}")
    @Operation(summary = "删除指标", description = """
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、PROJECT_METRIC_NOT_FOUND(40456)、UNAUTHORIZED(40100)。""")
    public Result<Void> deleteMetric(@PathVariable Long projectId, @PathVariable Long metricId) {
        projectSettingAppService.deleteMetric(projectId, metricId);
        return Result.success();
    }

    @PostMapping("/{projectId}/metrics:init-from-modality")
    @Operation(summary = "按分子形式补齐指标", description = """
            取该分子形式「建议权重 > 0」且 applies_to 覆盖它的指标，跳过已存在的。
            用户手调过的权重不会被覆盖 —— 只补缺失的。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、MODALITY_MISMATCH(40043)、UNAUTHORIZED(40100)。""")
    public Result<List<ProjectMetricResponse>> initMetricsFromModality(@PathVariable Long projectId) {
        return Result.success(projectSettingAppService.initMetricsFromModality(projectId));
    }

    @GetMapping("/{projectId}/risks")
    @Operation(summary = "风险关注点列表", description = """
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、UNAUTHORIZED(40100)。""")
    public Result<List<ProjectRiskResponse>> listRisks(@PathVariable Long projectId) {
        return Result.success(projectSettingAppService.listRisks(projectId));
    }

    @PostMapping("/{projectId}/risks")
    @Operation(summary = "新增风险关注点", description = """
            mustVerify=true 的风险点在决策阶段会被强制要求实测证据。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、VALIDATION_FAILED(40000)、
            BUSINESS_CONFLICT(40901)、UNAUTHORIZED(40100)。""")
    public Result<ProjectRiskResponse> addRisk(@PathVariable Long projectId,
                                               @Valid @RequestBody ProjectRiskUpsertRequest request) {
        return Result.success(projectSettingAppService.addRisk(projectId, request));
    }

    @PutMapping("/{projectId}/risks/{riskId}")
    @Operation(summary = "修改风险关注点", description = """
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、PROJECT_RISK_NOT_FOUND(40457)、
            VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。""")
    public Result<ProjectRiskResponse> updateRisk(@PathVariable Long projectId,
                                                  @PathVariable Long riskId,
                                                  @Valid @RequestBody ProjectRiskUpsertRequest request) {
        return Result.success(projectSettingAppService.updateRisk(projectId, riskId, request));
    }

    @DeleteMapping("/{projectId}/risks/{riskId}")
    @Operation(summary = "删除风险关注点", description = """
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、PROJECT_RISK_NOT_FOUND(40457)、UNAUTHORIZED(40100)。""")
    public Result<Void> deleteRisk(@PathVariable Long projectId, @PathVariable Long riskId) {
        projectSettingAppService.deleteRisk(projectId, riskId);
        return Result.success();
    }
}
