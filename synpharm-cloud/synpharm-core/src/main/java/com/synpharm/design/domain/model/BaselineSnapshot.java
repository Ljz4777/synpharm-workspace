package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * baseline_snapshot · 需求基线快照（冻结，不可变）。
 *
 * <p><b>为什么存在</b>：生成任务 S0 的输入是 <code>baseline_snapshot_id</code> 而不是项目当前状态 ——
 * 「只读快照，不读项目当前状态，用户中途改约束不影响本任务」（06 文档 §3.1）。
 *
 * <p><b>八块</b>（完整性校验的对象）：靶点+主靶点角色 / 靶点结构+口袋 / 分子形式 /
 * 硬约束(≥1) / 软约束+权重 / 评估指标清单 / 配额+min_candidates / 风险关注点。
 * 全部以 JSON 存：快照只被整体读取与哈希比对，不被 JOIN 查询。
 *
 * <p><b>不可变</b>：没有 deleted 列，也没有 update 语义。唯一允许的状态变更是
 * ACTIVE → SUPERSEDED（被新版本取代）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("baseline_snapshot")
public class BaselineSnapshot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 快照编号 BS+yyMMddHHmmss+随机 */
    private String snapshotNo;

    private Long projectId;

    /** 冻结时的项目版本号；与 project_id 联合唯一 */
    private Integer projectVersion;

    /** 冻结操作人 */
    private Long userId;

    /** 块1 靶点列表 + 主靶点角色 */
    private String targetsJson;

    /** 块2 prep 后的结构 URI/SHA256/口袋 */
    private String structureJson;

    /** 块3 分子形式 */
    private String modalityCode;

    /** 块4 硬约束快照（≥1 条） */
    private String hardRulesJson;

    /** 块5 软约束 + 权重 */
    private String softRulesJson;

    /** 块6 指标清单 + 权重 + 目标 + 误差带 */
    private String metricsJson;

    /** 块7 GPUh / 湿实验名额 / min_candidates */
    private String quotaJson;

    /** 块8 风险关注点 */
    private String risksJson;

    /** 逐块校验结果 {block: ok|missing|warn} */
    private String completenessJson;

    /** 八块内容的 sha256，用于幂等与审计 */
    private String contentHash;

    /** ACTIVE / SUPERSEDED */
    private String status;

    /** 被哪个快照取代 */
    private Long supersededBy;

    private LocalDateTime createdAt;
}
