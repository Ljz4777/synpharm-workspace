package com.synpharm.design.application;

import com.synpharm.design.api.dto.DecisionActionRequest;
import com.synpharm.design.api.dto.DecisionCreateRequest;
import com.synpharm.design.api.dto.DecisionOverrideRequest;
import com.synpharm.design.api.dto.DecisionReplayResponse;
import com.synpharm.design.api.dto.DecisionResponse;
import com.synpharm.design.api.dto.RiskAcceptanceRequest;
import java.util.List;

/**
 * 决策应用服务（07 §2 的 D0~D8）。
 *
 * <p>E2~E5 产出候选建议（D0~D5）；E6~E8 补上人来拍板的三步：
 * 人工调整（D6，理由必填）、风险接受（D7，必须验证项不可接受）、确认与回放（D8，指纹不一致不许确认）。
 *
 * @author SynPharm Team
 */
public interface DecisionAppService {

    /** 新建决策（D0~D5），返回 DRAFT 记录与候选建议 */
    DecisionResponse create(Long projectId, DecisionCreateRequest request);

    /** 决策详情（含候选明细、否决清单、风险接受） */
    DecisionResponse get(Long decisionId);

    /** 项目决策历史（新→旧）；不带候选明细 */
    List<DecisionResponse> listByProject(Long projectId);

    /**
     * 人工调整候选去向（D6）。理由必填；被 BLOCK 否决或硬约束复核不通过的候选不能改为入选。
     */
    DecisionResponse override(Long decisionId, Long candidateId, DecisionOverrideRequest request);

    /**
     * 接受某个风险点（D7）。粒度为逐候选逐风险点；「必须验证」项返回 409。
     */
    DecisionResponse acceptRisk(Long decisionId, Long candidateId, RiskAcceptanceRequest request);

    /** 用当前输入与规则重算并逐条比对（D8 / B4），返回差异清单 */
    DecisionReplayResponse replay(Long decisionId);

    /**
     * 确认冻结（D8）。前置：草稿 / 至少一个候选入选 / 回放输入指纹一致。
     * 确认后不可再改，要改走新版本。
     */
    DecisionResponse confirm(Long decisionId, DecisionActionRequest request);

    /** 转暂缓（补数据后重开）。理由必填 */
    DecisionResponse defer(Long decisionId, DecisionActionRequest request);

    /** 取消（仅草稿）。理由必填 */
    DecisionResponse cancel(Long decisionId, DecisionActionRequest request);
}