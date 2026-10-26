package com.synpharm.design.application;

import com.synpharm.design.api.dto.BaselinePreviewResponse;
import com.synpharm.design.api.dto.BaselineResponse;
import java.util.List;

/**
 * 需求基线用例（06 文档 §3）。
 *
 * <p>基线是<b>生成任务的唯一输入</b>：S0 只读快照、不读项目当前状态，
 * 所以用户中途改约束不会影响已提交的任务。这要求冻结出来的快照必须自足且不可变。
 *
 * @author SynPharm Team
 */
public interface BaselineAppService {

    /**
     * 预检：现在冻结会缺什么。不落库。
     *
     * <p>同时返回内容哈希，前端在用完向导后带着它调 {@link #freeze}，
     * 用来发现「预检之后别人改过项目」。
     */
    BaselinePreviewResponse preview(Long projectId);

    /**
     * 冻结当前项目定义为基线快照。
     *
     * <p>幂等：同样内容重复冻结返回已存在的快照，不产生新版本。
     *
     * @param expectedContentHash 预检时拿到的哈希；为空则不校验（允许直接冻结）
     */
    BaselineResponse freeze(Long projectId, String expectedContentHash);

    /** 当前生效的基线（status=ACTIVE）；没有则抛 {@code BASELINE_NOT_FOUND} */
    BaselineResponse current(Long projectId);

    /** 全部历史快照，新→旧 */
    List<BaselineResponse> history(Long projectId);
}
