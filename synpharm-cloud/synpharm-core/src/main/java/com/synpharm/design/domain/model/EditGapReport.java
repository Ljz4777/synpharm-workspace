package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * edit_gap_report · E1 缺口诊断（06 §5.2）。
 *
 * <p>只认 {@code evalFreezeId}，不接受「当前分数」——
 * 否则同一候选两次诊断用的是不同尺子。
 *
 * @author SynPharm Team
 */
@Data
@TableName("edit_gap_report")
public class EditGapReport {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String reportNo;
    private Long projectId;
    private Long candidateId;
    private Integer versionNo;

    /** 只认冻结快照 */
    private Long evalFreezeId;

    /** 缺口数组（含 attribution / editable / difficulty） */
    private String gapsJson;

    /** 已达标的维度 —— 保护名单，E3 回归守卫的输入 */
    private String noGapJson;

    /** 当前手段修不了的缺口 */
    private String unfixableJson;

    /** 建议修复顺序 */
    private String recommendedOrder;

    private LocalDateTime createdAt;
}
