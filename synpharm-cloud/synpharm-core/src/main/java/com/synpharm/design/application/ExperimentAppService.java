package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ExperimentCreateRequest;
import com.synpharm.design.api.dto.ExperimentResponse;

/**
 * 湿实验台账用例。
 *
 * <p>{@code errorDelta} 与 {@code isHit} 一律服务端计算，客户端传不进来（契约要求）。
 *
 * @author SynPharm Team
 */
public interface ExperimentAppService {

    /** 实验台账（可按项目 / 检测类型 / 结果状态过滤） */
    PageResult<ExperimentResponse> list(Long projectId, String assayType, String resultStatus, int page, int pageSize);

    /** 详情（含预测-实测对比） */
    ExperimentResponse get(Long id);

    /** 录入：服务端算 errorDelta / isHit，返回 201 */
    ExperimentResponse create(ExperimentCreateRequest request);

    /** 补录/更正结果：不传的字段保持原值 */
    ExperimentResponse patch(Long id, ExperimentCreateRequest request);
}
