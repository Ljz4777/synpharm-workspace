package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * evaluation_freeze · 评估冻结快照。
 *
 * <p><b>为什么必须要「冻结」而不是「查当前分数」</b>：编辑与决策的输入契约是
 * {@code eval_freeze_id}。若两边各自读「当前分数」，那么在两次读取之间有人改了
 * 指标权重或阈值，改前改后就不是同一把尺子，{@code edit_delta_report} 会得出
 * 看似精确却无意义的差值。
 *
 * <p>{@code contentHash} 覆盖「指标配置 + 分数」：同哈希即同结论，可用于幂等与审计。
 *
 * @author SynPharm Team
 */
@Data
@TableName("evaluation_freeze")
public class EvaluationFreeze {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 冻结编号 EV+yyMMddHHmmss+随机 */
    private String freezeNo;

    private Long projectId;

    private Long candidateId;

    /** 被评估的候选版本号（root=1） */
    private Integer versionNo;

    /** 来源 evaluation_run；最小评估直接算，可为空 */
    private Long evalRunId;

    /** {metric: {value, normalized, passed, risk}} */
    private String scoresJson;

    /** 全部硬约束是否通过 */
    private Integer hardPassed;

    /** 综合分（权重归一化后 0~100）；只代表 weightCoverage 覆盖的那部分 */
    private BigDecimal totalScore;

    /**
     * 可算权重占比 0~1。
     *
     * <p>目前小分子 10 个指标里有 6 个算不出来（SA_SCORE / 相似度 / DTI / DDI /
     * 选择性 / 对接分），可算权重只占 0.35。不把这件事显式落库，决策阶段会拿一个
     * 「看起来 80 分」的数字做取舍 —— 而那个 80 分其实只来自三分之一的指标。
     */
    private BigDecimal weightCoverage;

    /** 权重里有但算不出来的指标 —— 诚实记账，不是错误 */
    private String missingMetrics;

    /** 未能判定的规则码。没检查 ≠ 检查通过 */
    private String skippedRules;

    private String engineVersions;

    private String calibrationId;

    private String contentHash;

    private LocalDateTime createdAt;
}
