package com.synpharm.design.application;

import com.synpharm.design.api.dto.ProjectMetricResponse;
import com.synpharm.design.api.dto.ProjectMetricUpsertRequest;
import com.synpharm.design.api.dto.ProjectRiskResponse;
import com.synpharm.design.api.dto.ProjectRiskUpsertRequest;
import java.util.List;

/**
 * 项目级设置用例：评估指标与风险关注点（06 文档 §3.2 块6 / 块8）。
 *
 * <p>这两张表是基线八块里最「需要人手填」的两块，所以单独成一组用例，
 * 而不是塞进 {@code ProjectAppService} 的创建流程 —— 向导允许用户先建项目、后补指标。
 *
 * @author SynPharm Team
 */
public interface ProjectSettingAppService {

    List<ProjectMetricResponse> listMetrics(Long projectId);

    ProjectMetricResponse addMetric(Long projectId, ProjectMetricUpsertRequest request);

    ProjectMetricResponse updateMetric(Long projectId, Long metricId, ProjectMetricUpsertRequest request);

    void deleteMetric(Long projectId, Long metricId);

    /**
     * 按项目所选分子形式的 {@code defaultDimensions} 一键补齐指标。
     *
     * <p>只补缺失的维度，不动已有的 —— 用户手调过的权重不能被覆盖。
     *
     * @return 补齐后的完整指标列表
     */
    List<ProjectMetricResponse> initMetricsFromModality(Long projectId);

    List<ProjectRiskResponse> listRisks(Long projectId);

    ProjectRiskResponse addRisk(Long projectId, ProjectRiskUpsertRequest request);

    ProjectRiskResponse updateRisk(Long projectId, Long riskId, ProjectRiskUpsertRequest request);

    void deleteRisk(Long projectId, Long riskId);
}
