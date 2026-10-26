package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.ProjectCreatedResponse;
import com.synpharm.design.api.dto.ProjectCreateRequest;
import com.synpharm.design.api.dto.ProjectPatchRequest;
import com.synpharm.design.api.dto.ProjectResponse;
import com.synpharm.design.api.dto.ProjectTargetBindRequest;
import com.synpharm.design.api.dto.ProjectTargetResponse;

/**
 * 项目生命周期用例。
 *
 * <p>{@code READY} 是推导态：由「已绑靶点 且 已激活约束集」推导，接口不提供手工置位。
 * 每次读写都会重算，并给出 {@code readyBlockers}（红线 R2：不得静默就绪）。
 *
 * @author SynPharm Team
 */
public interface ProjectAppService {

    /** 我的项目列表（可选状态/关键词过滤） */
    PageResult<ProjectResponse> list(String status, String keyword, int page, int pageSize);

    /** 详情（含靶点、轮次、候选统计） */
    ProjectResponse get(Long id);

    /** 创建：同事务内建项目 + 绑靶点 +（可选）内联约束集并激活 */
    ProjectCreatedResponse create(ProjectCreateRequest request);

    /** 编辑基础字段（name/goalText/diseaseId/modalityId） */
    ProjectResponse update(Long id, ProjectPatchRequest request);

    /** 绑定靶点到项目 */
    ProjectTargetResponse bindTarget(Long projectId, ProjectTargetBindRequest request);

    /** 解绑靶点 */
    void unbindTarget(Long projectId, Long targetId);

    /** 项目当前生效的约束集（含规则）；尚未绑定时返回 {@code null} */
    ConstraintSetResponse getProjectConstraints(Long projectId);

    /**
     * 绑定约束集为项目生效约束。
     *
     * @param activate 为 true 且约束集还是 DRAFT 时，先自检并激活再绑定
     */
    ConstraintSetResponse bindConstraintSet(Long projectId, Long constraintSetId, boolean activate);

    /** 结项：ACTIVE / READY → COMPLETED，写结项小结 */
    ProjectResponse close(Long id, String summary);

    /** 归档：COMPLETED / CANCELLED → ARCHIVED */
    ProjectResponse archive(Long id);
}
