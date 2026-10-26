package com.synpharm.design.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.synpharm.design.domain.model.MetricDefinition;
import com.synpharm.design.infrastructure.persistence.MetricDefinitionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 命中判定口径测试。
 *
 * <p>{@code is_hit} 直接进命中率统计，判错的代价不对称：把「未知」写成 {@code false}
 * 会一路污染下游报表，而且不会有任何报错。所以这里的重点是**判不出来的场景一律 null**：
 * 无数值结果、未记预测指标、指标未注册、项目没配该维度目标、目标缺阈值。
 *
 * <p>另一半重点是有界值（{@code >10000 nM} 这类）只认「可证伪」的方向：
 * 要求 {@code <=100} 而实测 {@code >10000} ⇒ 可证不命中；要求 {@code <=100} 而实测
 * {@code >50} ⇒ 未知，不能顺手判成命中。
 *
 * <p>有界值的**边界**单独成组：{@code GTE}/{@code LTE} 的真值落在半开区间里，
 * 端点恰等于阈值时不能当成严格界用（实测 {@code ≥100}、要求 {@code >100} ⇒ 不可判定），
 * 这类「区间内所有取值都满足/都不满足」才允许给结论。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HitJudgeTest {

    private static final String METRIC_CODE = "MOL_IC50";

    /** metric_definition 里的维度是大写，项目 goal_metric_json 的键不保证大小写一致 */
    private static final String DIMENSION = "AFFINITY";

    @Mock private MetricDefinitionMapper metricDefinitionMapper;

    private HitJudge hitJudge;

    @BeforeEach
    void setUp() {
        // findMetric 用 lambdaQuery 把 getMetricCode 翻成列名，单测里没有 Spring/MyBatis 上下文
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MetricDefinition.class);
        hitJudge = new HitJudge(metricDefinitionMapper);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(metric(METRIC_CODE, DIMENSION)));
    }

    private static MetricDefinition metric(String code, String dimension) {
        MetricDefinition m = new MetricDefinition();
        m.setId(9L);
        m.setMetricCode(code);
        m.setDimension(dimension);
        return m;
    }

    private static Map<String, Object> goalEntry(String operator, Object threshold) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("operator", operator);
        if (threshold != null) {
            entry.put("threshold", threshold);
        }
        entry.put("unit", "nM");
        return entry;
    }

    private static Map<String, Object> goal(String dimensionKey, String operator, Object threshold) {
        Map<String, Object> goal = new HashMap<>();
        goal.put(dimensionKey, goalEntry(operator, threshold));
        return goal;
    }

    /** 目标为 {@code criterionOp threshold}（AFFINITY 维度），实测 {@code valueOp resultNum} */
    private HitJudge.Verdict judge(String criterionOp, Object threshold, String valueOp, String resultNum) {
        return hitJudge.judge(goal(DIMENSION, criterionOp, threshold),
                resultNum == null ? null : new BigDecimal(resultNum), valueOp, METRIC_CODE);
    }

    // ------------------------------------------------------------ 判不出来的场景

    @Test
    @DisplayName("无数值结果：不可判定（不写成 false 污染命中率）")
    void nullResultIsUnjudgeable() {
        HitJudge.Verdict verdict = judge("LTE", 100, null, null);

        assertThat(verdict.hit()).isNull();
        assertThat(verdict.reason()).contains("无数值结果");
    }

    @Test
    @DisplayName("未记录预测指标：不可判定（无从确定判定维度）")
    void blankMetricCodeIsUnjudgeable() {
        HitJudge.Verdict nullCode = hitJudge.judge(goal(DIMENSION, "LTE", 100), new BigDecimal("10"), null, null);
        HitJudge.Verdict blankCode = hitJudge.judge(goal(DIMENSION, "LTE", 100), new BigDecimal("10"), null, "   ");

        assertThat(nullCode.hit()).isNull();
        assertThat(nullCode.reason()).contains("未记录预测指标");
        assertThat(blankCode.hit()).isNull();
        assertThat(blankCode.reason()).contains("未记录预测指标");
    }

    @Test
    @DisplayName("指标未在 metric_definition 注册：不可判定")
    void unregisteredMetricIsUnjudgeable() {
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of());

        HitJudge.Verdict verdict = judge("LTE", 100, null, "10");

        assertThat(verdict.hit()).isNull();
        assertThat(verdict.reason()).contains("未在 metric_definition 注册").contains(METRIC_CODE);
    }

    @Test
    @DisplayName("项目未配置 goal_metric_json：不可判定")
    void missingGoalIsUnjudgeable() {
        HitJudge.Verdict nullGoal = hitJudge.judge(null, new BigDecimal("10"), null, METRIC_CODE);
        HitJudge.Verdict emptyGoal = hitJudge.judge(Map.of(), new BigDecimal("10"), null, METRIC_CODE);

        assertThat(nullGoal.hit()).isNull();
        assertThat(nullGoal.reason()).contains("项目未配置优化目标");
        assertThat(emptyGoal.hit()).isNull();
        assertThat(emptyGoal.reason()).contains("项目未配置优化目标");
    }

    @Test
    @DisplayName("项目未设该维度的目标：不可判定")
    void missingDimensionGoalIsUnjudgeable() {
        HitJudge.Verdict verdict = hitJudge.judge(goal("STABILITY", "LTE", 100),
                new BigDecimal("10"), null, METRIC_CODE);

        assertThat(verdict.hit()).isNull();
        assertThat(verdict.reason()).contains("未设该维度的优化目标").contains(DIMENSION);
    }

    @Test
    @DisplayName("目标缺 threshold 或 operator：不可判定")
    void malformedGoalEntryIsUnjudgeable() {
        HitJudge.Verdict noThreshold = hitJudge.judge(goal(DIMENSION, "LTE", null),
                new BigDecimal("10"), null, METRIC_CODE);
        HitJudge.Verdict noOperator = hitJudge.judge(goal(DIMENSION, null, 100),
                new BigDecimal("10"), null, METRIC_CODE);

        assertThat(noThreshold.hit()).isNull();
        assertThat(noThreshold.reason()).contains("缺少 threshold 或 operator");
        assertThat(noOperator.hit()).isNull();
        assertThat(noOperator.reason()).contains("缺少 threshold 或 operator");
    }

    // ------------------------------------------------------------ 精确值（算子为空）

    @Test
    @DisplayName("精确值：阈值 100 时 LTE 100 -> 命中，LT 100 -> 不命中")
    void exactBoundaryLteAndLt() {
        assertThat(judge("LTE", 100, null, "100").hit()).isTrue();
        assertThat(judge("LT", 100, null, "100").hit()).isFalse();
    }

    @Test
    @DisplayName("精确值：阈值 100 时 GTE 100 -> 命中，GT 100 -> 不命中，GT 101 -> 命中")
    void exactBoundaryGteAndGt() {
        assertThat(judge("GTE", 100, null, "100").hit()).isTrue();
        assertThat(judge("GT", 100, null, "100").hit()).isFalse();
        assertThat(judge("GT", 100, null, "101").hit()).isTrue();
    }

    @Test
    @DisplayName("精确值：EQ 仅在恰好相等时命中")
    void exactEqOnlyWhenEqual() {
        assertThat(judge("EQ", 100, null, "100").hit()).isTrue();
        assertThat(judge("EQ", 100, null, "101").hit()).isFalse();
    }

    @Test
    @DisplayName("显式 EQ 算子与算子缺省等价（都按精确值判定）")
    void explicitEqOperatorIsTreatedAsExact() {
        HitJudge.Verdict verdict = judge("LTE", 100, "EQ", "100");

        assertThat(verdict.hit()).isTrue();
        assertThat(verdict.reason()).isNull();
    }

    // ------------------------------------------------------------ 有界值（可证伪方向）

    @Test
    @DisplayName("有界值 GT 且实测 >= 阈值：可证「不小于」——要求下限的方向命中，要求上限的方向不命中")
    void boundedHigherThanThreshold() {
        assertThat(judge("LT", 100, "GT", "150").hit()).isFalse();
        assertThat(judge("LTE", 100, "GT", "150").hit()).isFalse();
        assertThat(judge("EQ", 100, "GT", "150").hit()).isFalse();
        assertThat(judge("GT", 100, "GT", "150").hit()).isTrue();
        assertThat(judge("GTE", 100, "GT", "150").hit()).isTrue();
        // 边界：实测恰好等于阈值，>= 仍然成立
        assertThat(judge("GT", 100, "GT", "100").hit()).isTrue();
        assertThat(judge("LTE", 100, "GT", "100").hit()).isFalse();
        // 实测算子大小写不敏感
        assertThat(judge("LT", 100, "gt", "150").hit()).isFalse();
    }

    @Test
    @DisplayName("有界值 GT 但实测低于阈值：区间与阈值的关系不可证明 -> null")
    void boundedHigherThanBelowThresholdIsUnjudgeable() {
        HitJudge.Verdict towardUpper = judge("LTE", 100, "GT", "50");
        HitJudge.Verdict towardLower = judge("GT", 100, "GT", "50");

        assertThat(towardUpper.hit()).isNull();
        assertThat(towardUpper.reason()).contains("不可证明");
        assertThat(towardLower.hit()).isNull();
    }

    @Test
    @DisplayName("有界值 LT 且实测 <= 阈值：可证「不大于」——要求上限的方向命中，要求下限的方向不命中")
    void boundedLowerThanThreshold() {
        assertThat(judge("GT", 100, "LT", "50").hit()).isFalse();
        assertThat(judge("GTE", 100, "LT", "50").hit()).isFalse();
        assertThat(judge("EQ", 100, "LT", "50").hit()).isFalse();
        assertThat(judge("LT", 100, "LT", "50").hit()).isTrue();
        assertThat(judge("LTE", 100, "LT", "50").hit()).isTrue();
        // 实测算子大小写不敏感
        assertThat(judge("LT", 100, "lte", "50").hit()).isTrue();
    }

    @Test
    @DisplayName("有界值 LT 但实测高于阈值：不可证明 -> null")
    void boundedLowerThanAboveThresholdIsUnjudgeable() {
        HitJudge.Verdict verdict = judge("GT", 100, "LT", "150");

        assertThat(verdict.hit()).isNull();
        assertThat(verdict.reason()).contains("不可证明");
    }

    // -------------------------------------------- 半开区间端点（原缺陷点，逐条钉死）
    //
    // 实测是 GTE/LTE 时真值落在**半开**区间里：≥100 的真值可能就是 100。
    // 修复前这里会把「≥100 对 >100」判成命中（把非严格界当成严格界用），
    // 端点情形因此单独成组：只有「区间内所有取值都满足 / 都不满足」才允许给结论。

    @Test
    @DisplayName("有界值 GT/LT 实测恰等于阈值：严格界端点仍可证（修复不得波及）")
    void strictBoundedEndpointsRemainProvable() {
        // 实测 >100：真值必大于 100
        assertThat(judge("GT", 100, "GT", "100").hit()).isTrue();
        assertThat(judge("GTE", 100, "GT", "100").hit()).isTrue();
        assertThat(judge("LT", 100, "GT", "100").hit()).isFalse();
        assertThat(judge("LTE", 100, "GT", "100").hit()).isFalse();
        assertThat(judge("EQ", 100, "GT", "100").hit()).isFalse();
        // 实测 <100：真值必小于 100
        assertThat(judge("LT", 100, "LT", "100").hit()).isTrue();
        assertThat(judge("LTE", 100, "LT", "100").hit()).isTrue();
        assertThat(judge("GT", 100, "LT", "100").hit()).isFalse();
        assertThat(judge("GTE", 100, "LT", "100").hit()).isFalse();
        assertThat(judge("EQ", 100, "LT", "100").hit()).isFalse();
    }

    @Test
    @DisplayName("有界值 GTE 恰等于阈值：只证非严格方向（GTE 命中、LT 不命中），GT/LTE/EQ 一律 null")
    void boundedGteAtThresholdIsOnlyProvableOnTheNonStrictSide() {
        assertThat(judge("GTE", 100, "GTE", "100").hit()).isTrue();
        assertThat(judge("LT", 100, "GTE", "100").hit()).isFalse();

        HitJudge.Verdict strictCriterion = judge("GT", 100, "GTE", "100");
        HitJudge.Verdict upperCriterion = judge("LTE", 100, "GTE", "100");
        HitJudge.Verdict equalCriterion = judge("EQ", 100, "GTE", "100");

        assertThat(strictCriterion.hit()).isNull();
        assertThat(upperCriterion.hit()).isNull();
        assertThat(equalCriterion.hit()).isNull();
        assertThat(strictCriterion.reason()).contains(">= 100").contains("不可证明");
    }

    @Test
    @DisplayName("有界值 GTE 高于阈值：两侧都可证（GT/GTE 命中，LT/LTE/EQ 不命中）")
    void boundedGteAboveThresholdProvesBothSides() {
        assertThat(judge("GT", 100, "GTE", "200").hit()).isTrue();
        assertThat(judge("GTE", 100, "GTE", "200").hit()).isTrue();
        assertThat(judge("LT", 100, "GTE", "200").hit()).isFalse();
        assertThat(judge("LTE", 100, "GTE", "200").hit()).isFalse();
        assertThat(judge("EQ", 100, "GTE", "200").hit()).isFalse();
    }

    @Test
    @DisplayName("有界值 GTE 低于阈值：一律 null（真值可能落在阈值任意一侧）")
    void boundedGteBelowThresholdIsUnjudgeable() {
        HitJudge.Verdict towardLower = judge("GTE", 100, "GTE", "50");
        HitJudge.Verdict strictLower = judge("GT", 100, "GTE", "50");

        assertThat(towardLower.hit()).isNull();
        assertThat(strictLower.hit()).isNull();
        assertThat(judge("LT", 100, "GTE", "50").hit()).isNull();
        assertThat(judge("LTE", 100, "GTE", "50").hit()).isNull();
        assertThat(judge("EQ", 100, "GTE", "50").hit()).isNull();
        assertThat(towardLower.reason()).contains(">= 50").contains("不可证明");
    }

    @Test
    @DisplayName("有界值 LTE 恰等于阈值：只证非严格方向（LTE 命中、GT 不命中），LT/GTE/EQ 一律 null")
    void boundedLteAtThresholdIsOnlyProvableOnTheNonStrictSide() {
        assertThat(judge("LTE", 100, "LTE", "100").hit()).isTrue();
        assertThat(judge("GT", 100, "LTE", "100").hit()).isFalse();

        HitJudge.Verdict strictCriterion = judge("LT", 100, "LTE", "100");
        HitJudge.Verdict lowerCriterion = judge("GTE", 100, "LTE", "100");
        HitJudge.Verdict equalCriterion = judge("EQ", 100, "LTE", "100");

        assertThat(strictCriterion.hit()).isNull();
        assertThat(lowerCriterion.hit()).isNull();
        assertThat(equalCriterion.hit()).isNull();
        assertThat(strictCriterion.reason()).contains("<= 100").contains("不可证明");
    }

    @Test
    @DisplayName("有界值 LTE 低于阈值：两侧都可证（LT/LTE 命中，GT/GTE/EQ 不命中）")
    void boundedLteBelowThresholdProvesBothSides() {
        assertThat(judge("LT", 100, "LTE", "50").hit()).isTrue();
        assertThat(judge("LTE", 100, "LTE", "50").hit()).isTrue();
        assertThat(judge("GT", 100, "LTE", "50").hit()).isFalse();
        assertThat(judge("GTE", 100, "LTE", "50").hit()).isFalse();
        assertThat(judge("EQ", 100, "LTE", "50").hit()).isFalse();
    }

    @Test
    @DisplayName("有界值 LTE 高于阈值：一律 null（真值可能落在阈值任意一侧）")
    void boundedLteAboveThresholdIsUnjudgeable() {
        HitJudge.Verdict towardUpper = judge("LTE", 100, "LTE", "200");

        assertThat(towardUpper.hit()).isNull();
        assertThat(judge("LT", 100, "LTE", "200").hit()).isNull();
        assertThat(judge("GT", 100, "LTE", "200").hit()).isNull();
        assertThat(judge("GTE", 100, "LTE", "200").hit()).isNull();
        assertThat(judge("EQ", 100, "LTE", "200").hit()).isNull();
        assertThat(towardUpper.reason()).contains("<= 200").contains("不可证明");
    }

    // ---------------------------------------------------- 不可判定的 reason 措辞

    @Test
    @DisplayName("reason：有界实测值带自己的算子（> / >= / < / <= 数值）")
    void reasonDescribesBoundedValueWithItsOperator() {
        assertThat(judge("LTE", 100, "GT", "50").reason()).contains("> 50").contains("不可证明");
        assertThat(judge("GT", 100, "LT", "150").reason()).contains("< 150").contains("不可证明");
        assertThat(judge("LTE", 20000, "GT", "10000").reason()).contains("> 10000");
        assertThat(judge("GT", 100, "GTE", "100").reason()).contains(">= 100");
        assertThat(judge("LT", 100, "LTE", "100").reason()).contains("<= 100");
    }

    @Test
    @DisplayName("reason：精确实测值写成「= 数值」，EQ/缺省算子都不带算子前缀")
    void reasonKeepsExactFormForExactValue() {
        HitJudge.Verdict noOperator = judge("BETWEEN", 100, null, "100");
        HitJudge.Verdict eqOperator = judge("BETWEEN", 100, "EQ", "100");
        HitJudge.Verdict equalsOperator = judge("BETWEEN", 100, "=", "100");

        assertThat(noOperator.reason()).contains("= 100").doesNotContain("有界值");
        assertThat(eqOperator.reason()).contains("= 100").doesNotContain("EQ 100").doesNotContain("有界值");
        assertThat(equalsOperator.reason()).contains("= 100").doesNotContain("有界值");
    }

    // ------------------------------------------------------------ 未知算子

    @Test
    @DisplayName("未知的目标算子(BETWEEN)：不可判定 -> null，且不再误报「有界值」措辞")
    void unknownCriterionOperatorIsUnjudgeable() {
        HitJudge.Verdict exact = judge("BETWEEN", 100, null, "100");
        HitJudge.Verdict bounded = judge("BETWEEN", 100, "GT", "150");

        assertThat(exact.hit()).isNull();
        assertThat(exact.reason()).contains("不可证明").contains("= 100").doesNotContain("有界值");
        assertThat(bounded.hit()).isNull();
        assertThat(bounded.reason()).contains("不可证明").contains("> 150").doesNotContain("有界值");
    }

    @Test
    @DisplayName("未知的实测算子：不可判定 -> null，reason 原样保留算子")
    void unknownValueOperatorIsUnjudgeable() {
        HitJudge.Verdict verdict = judge("LTE", 100, "BETWEEN", "100");

        assertThat(verdict.hit()).isNull();
        assertThat(verdict.reason()).contains("不可证明").contains("BETWEEN 100");
    }

    // ------------------------------------------------------------ 维度匹配

    @Test
    @DisplayName("维度匹配大小写不敏感：目标的键小写、指标维度大写仍能对上")
    void dimensionMatchingIsCaseInsensitive() {
        HitJudge.Verdict lowerGoalKey = hitJudge.judge(goal("affinity", "LTE", 100),
                new BigDecimal("50"), null, METRIC_CODE);
        assertThat(lowerGoalKey.hit()).isTrue();
        assertThat(lowerGoalKey.reason()).isNull();

        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(metric(METRIC_CODE, "affinity")));
        HitJudge.Verdict lowerMetricDimension = hitJudge.judge(goal(DIMENSION, "LTE", 100),
                new BigDecimal("50"), null, METRIC_CODE);
        assertThat(lowerMetricDimension.hit()).isTrue();
    }
}
