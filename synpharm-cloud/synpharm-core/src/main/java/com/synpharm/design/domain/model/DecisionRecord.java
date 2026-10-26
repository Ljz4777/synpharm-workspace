package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * decision_record · 决策记录（07 §5.1）。
 *
 * <p>三个字段是回放能力的全部（B4）：{@code weightsSnapshot} 是排序用的权重、
 * {@code ruleVersion} 是规则版本、{@code inputSnapshotHash} 是全部证据包 hash 的排序聚合。
 * 少了任何一个，「换个人按同样输入重算会不会得到同样结论」就答不上来。
 *
 * <p>阶段 E2~E5 只产生 {@code DRAFT}；确认/暂缓/取消在 E6~E8。
 *
 * @author SynPharm Team
 */
@Data
@TableName("decision_record")
public class DecisionRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 决策编号 DCN+yyMMddHHmmss+随机 */
    private String decisionNo;

    private Long projectId;

    private Long roundId;

    private Long baselineSnapshotId;

    /** 关联正式报告（报告审批与候选决策分离） */
    private Long reportId;

    /** 排序用的指标权重快照（回放必需） */
    private String weightsSnapshot;

    /** 排序 + 否决规则版本（回放必需） */
    private String ruleVersion;

    /** 全部证据包 hash 排序聚合（回放必需） */
    private String inputSnapshotHash;

    /** 确认时重算结果的 hash；E6~E8 才写 */
    private String replayHash;

    /** DRAFT / CONFIRMED / DEFERRED / CANCELLED */
    private String status;

    /**
     * 本次实际名额 = min(档位, 湿实验余额)，且至少 1。
     *
     * <p>它是派生值，但**必须落库**：读取时按当前项目配置重算，会在项目改配置后
     * 把历史决策的解释悄悄改写掉 —— 那正是「确认即不可改」要防的事。
     */
    private Integer capacity;

    private Integer selectedCount;

    private Integer backupCount;

    private Integer rejectedCount;

    private Integer deferredCount;

    private String decisionNote;

    private Long decidedBy;

    private LocalDateTime decidedAt;

    /** 上一版决策（CONFIRMED 后改动走新版本，不覆盖） */
    private Long revisionOf;

    private Integer versionNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
