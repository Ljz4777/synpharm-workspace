package com.synpharm.design.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.BaselinePreviewResponse;
import com.synpharm.design.domain.model.BindingPocket;
import com.synpharm.design.domain.model.BaselineBlock;
import com.synpharm.design.domain.model.ConstraintRule;
import com.synpharm.design.domain.model.ConstraintSet;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.MetricDefinition;
import com.synpharm.design.domain.model.ProjectMetric;
import com.synpharm.design.domain.model.ProjectRiskItem;
import com.synpharm.design.domain.model.ProjectTarget;
import com.synpharm.design.domain.model.Target;
import com.synpharm.design.domain.model.TargetStructure;
import com.synpharm.design.infrastructure.persistence.BindingPocketMapper;
import com.synpharm.design.infrastructure.persistence.ConstraintRuleMapper;
import com.synpharm.design.infrastructure.persistence.ConstraintSetMapper;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.MetricDefinitionMapper;
import com.synpharm.design.infrastructure.persistence.ProjectMetricMapper;
import com.synpharm.design.infrastructure.persistence.ProjectRiskItemMapper;
import com.synpharm.design.infrastructure.persistence.ProjectTargetMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
import com.synpharm.design.infrastructure.persistence.TargetStructureMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 八块装配测试。
 *
 * <p>三条要点：
 * <ol>
 *   <li><b>必需块缺项必须是 ERROR</b>：漏判会让不完整的基线通过冻结，
 *       生成阶段拿到的就是一个「能跑但没有约束」的空工单。</li>
 *   <li><b>content_hash 只随内容变</b>：同一份内容重复装配必须得到同一个哈希，
 *       这是冻结幂等的基础；否则每次点「冻结」都会生成新版本。</li>
 *   <li><b>可选块缺失不算错</b>：软约束与风险关注点为空是合法状态，不能堵住冻结。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaselineAssemblerTest {

    private static final long PROJECT = 900L;
    private static final long TARGET_ID = 1L;
    private static final long STRUCT_ID = 11L;
    private static final long MODALITY_ID = 71L;
    private static final long CONSTRAINT_SET_ID = 81L;

    @Mock private ProjectTargetMapper projectTargetMapper;
    @Mock private TargetMapper targetMapper;
    @Mock private TargetStructureMapper targetStructureMapper;
    @Mock private BindingPocketMapper bindingPocketMapper;
    @Mock private ConstraintSetMapper constraintSetMapper;
    @Mock private ConstraintRuleMapper constraintRuleMapper;
    @Mock private ProjectMetricMapper projectMetricMapper;
    @Mock private ProjectRiskItemMapper projectRiskItemMapper;
    @Mock private MetricDefinitionMapper metricDefinitionMapper;
    @Mock private DesignLibraryItemMapper libraryItemMapper;
    @Spy private JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    @InjectMocks private BaselineAssembler assembler;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 需要 TableInfo 才能把方法引用翻成列名
        for (Class<?> c : List.of(ProjectTarget.class, TargetStructure.class, BindingPocket.class,
                ConstraintRule.class, ProjectMetric.class, MetricDefinition.class, ProjectRiskItem.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), c);
        }
        stubEmpty();
    }

    /** 全部为空的最小场景 */
    private void stubEmpty() {
        when(projectTargetMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(targetStructureMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(bindingPocketMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(constraintRuleMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(projectMetricMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(metricDefinitionMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(projectRiskItemMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    private static DesignProject project(Integer minCandidates, BigDecimal gpuHours, Integer wetLab) {
        DesignProject p = new DesignProject();
        p.setId(PROJECT);
        p.setModalityId(MODALITY_ID);
        p.setActiveConstraintSetId(CONSTRAINT_SET_ID);
        p.setMinCandidates(minCandidates);
        p.setQuotaGpuHours(gpuHours);
        p.setQuotaWetLab(wetLab);
        p.setMoaCode("INHIBITOR");
        p.setDecisionCapacityMode("RATIO");
        p.setDecisionCapacityValue(new BigDecimal("0.200"));
        return p;
    }

    /** 连形式与约束集都没选的立项初始态 */
    private static DesignProject blankProject() {
        DesignProject p = new DesignProject();
        p.setId(PROJECT);
        return p;
    }

    private static Map<String, String> stateOf(BaselineAssembler.Assembled a) {
        return a.getPreview().getBlocks().stream()
                .collect(Collectors.toMap(BaselinePreviewResponse.BlockStatus::getCode,
                        BaselinePreviewResponse.BlockStatus::getState));
    }

    private static List<String> gapCodes(BaselineAssembler.Assembled a) {
        return a.getPreview().getGaps().stream()
                .map(BaselinePreviewResponse.Gap::getCode)
                .collect(Collectors.toList());
    }

    /** 补齐全部八块 */
    private void stubComplete() {
        ProjectTarget binding = new ProjectTarget();
        binding.setProjectId(PROJECT);
        binding.setTargetId(TARGET_ID);
        binding.setIsPrimary(1);
        binding.setRole("PRIMARY");
        binding.setSortNo(1);
        when(projectTargetMapper.selectList(any())).thenReturn(List.of(binding));

        Target target = new Target();
        target.setId(TARGET_ID);
        target.setName("IL-6R");
        target.setUniprotId("P08887");
        target.setTargetType("PROTEIN");
        when(targetMapper.selectById(TARGET_ID)).thenReturn(target);
        when(targetMapper.selectBatchIds(any())).thenReturn(List.of(target));

        TargetStructure structure = new TargetStructure();
        structure.setId(STRUCT_ID);
        structure.setTargetId(TARGET_ID);
        structure.setPdbId("1N26");
        structure.setChainId("A");
        structure.setIsPreferred(1);
        when(targetStructureMapper.selectList(any())).thenReturn(List.of(structure));

        BindingPocket pocket = new BindingPocket();
        pocket.setId(101L);
        pocket.setStructureId(STRUCT_ID);
        pocket.setPocketNo(1);
        pocket.setIsSelected(1);
        pocket.setDruggability(new BigDecimal("0.82"));
        when(bindingPocketMapper.selectList(any())).thenReturn(List.of(pocket));

        DesignLibraryItem modality = new DesignLibraryItem();
        modality.setId(MODALITY_ID);
        modality.setCode("MAB");
        modality.setName("单克隆抗体");
        modality.setCategory("MODALITY");
        modality.setEnabled(1);
        modality.setMetaJson("{\"seqRequired\":true,\"defaultDimensions\":[\"AFFINITY\"]}");
        when(libraryItemMapper.selectById(MODALITY_ID)).thenReturn(modality);

        ConstraintSet set = new ConstraintSet();
        set.setId(CONSTRAINT_SET_ID);
        set.setSetNo("CS-1");
        set.setName("默认约束集");
        set.setLogic("AND");
        set.setStatus("ACTIVE");
        when(constraintSetMapper.selectById(CONSTRAINT_SET_ID)).thenReturn(set);

        ConstraintRule hard = new ConstraintRule();
        hard.setId(201L);
        hard.setSetId(CONSTRAINT_SET_ID);
        hard.setEnabled(1);
        hard.setSortNo(1);
        hard.setSeverity("HARD");
        hard.setDimension("IMMUNOGENICITY");
        hard.setOperator("LTE");
        hard.setThresholdNum(new BigDecimal("5"));
        when(constraintRuleMapper.selectList(any())).thenReturn(List.of(hard));

        ProjectMetric metric = new ProjectMetric();
        metric.setId(301L);
        metric.setProjectId(PROJECT);
        metric.setEnabled(1);
        metric.setPriority(1);
        metric.setMetricCode("AFFINITY");
        metric.setWeight(new BigDecimal("0.5"));
        metric.setThreshold(new BigDecimal("10"));
        metric.setOperator("LTE");
        when(projectMetricMapper.selectList(any())).thenReturn(List.of(metric));

        MetricDefinition def = new MetricDefinition();
        def.setId(1L);
        def.setMetricCode("AFFINITY");
        def.setMetricName("亲和力");
        def.setDimension("AFFINITY");
        def.setUnit("nM");
        def.setBetter("LOWER");
        def.setEnabled(1);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(def));
    }

    // ------------------------------------------------------------------ 缺项

    @Test
    @DisplayName("连形式与约束集都没选：NO_MODALITY / NO_CONSTRAINT_SET，不是 INVALID/MISSING")
    void allEmptyBlocksMissing() {
        BaselineAssembler.Assembled a = assembler.assemble(blankProject());

        Map<String, String> state = stateOf(a);
        assertThat(state).containsEntry(BaselineBlock.TARGETS.getCode(), "MISSING")
                .containsEntry(BaselineBlock.STRUCTURE.getCode(), "MISSING")
                .containsEntry(BaselineBlock.MODALITY.getCode(), "MISSING")
                .containsEntry(BaselineBlock.HARD_RULES.getCode(), "MISSING")
                .containsEntry(BaselineBlock.METRICS.getCode(), "MISSING")
                .containsEntry(BaselineBlock.QUOTA.getCode(), "MISSING")
                // 可选块不堵冻结
                .containsEntry(BaselineBlock.SOFT_RULES.getCode(), "OK")
                .containsEntry(BaselineBlock.RISKS.getCode(), "OK");

        assertThat(a.isReady()).isFalse();
        assertThat(a.errorCount()).isGreaterThanOrEqualTo(6);
        // 没有主靶点时结构块报的是 NO_PRIMARY_TARGET —— 「不知道查哪个靶点」比「没有结构」更准确
        assertThat(gapCodes(a)).contains("NO_TARGET", "NO_PRIMARY_TARGET", "NO_MODALITY",
                "NO_CONSTRAINT_SET", "NO_METRIC", "NO_MIN_CANDIDATES");
        assertThat(a.getModalityCode()).isNull();
    }

    @Test
    @DisplayName("选了但字典里已没有该项：INVALID / MISSING，不回退到空实现")
    void selectedButGoneItemsAreFlagged() {
        // 这里故意不 stub libraryItemMapper / constraintSetMapper，模拟被停用或删除
        BaselineAssembler.Assembled a = assembler.assemble(project(20, null, null));

        assertThat(gapCodes(a)).contains("MODALITY_INVALID", "CONSTRAINT_SET_MISSING");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.MODALITY.getCode(), "MISSING")
                .containsEntry(BaselineBlock.HARD_RULES.getCode(), "MISSING");
        assertThat(a.isReady()).isFalse();
    }

    @Test
    @DisplayName("完整八块：ready=true，contentHash 为 64 位小写十六进制")
    void completeIsReady() {
        stubComplete();
        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("48"), 8));

        assertThat(a.isReady()).isTrue();
        assertThat(a.errorCount()).isZero();
        assertThat(a.getModalityCode()).isEqualTo("MAB");
        assertThat(a.getContentHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a.getTargetsJson()).contains("IL-6R");
        assertThat(a.getHardRulesJson()).contains("\"ruleId\":201");
        assertThat(a.getMetricsJson()).contains("AFFINITY");
        assertThat(a.getQuotaJson()).contains("\"minCandidates\":20");
    }

    @Test
    @DisplayName("有靶点但没指定主靶点：TARGETS 与 STRUCTURE 都 MISSING（结构无从依附）")
    void bindingWithoutPrimary() {
        ProjectTarget binding = new ProjectTarget();
        binding.setProjectId(PROJECT);
        binding.setTargetId(TARGET_ID);
        binding.setIsPrimary(0);
        when(projectTargetMapper.selectList(any())).thenReturn(List.of(binding));

        BaselineAssembler.Assembled a = assembler.assemble(project(20, null, null));

        assertThat(stateOf(a)).containsEntry(BaselineBlock.TARGETS.getCode(), "MISSING");
        assertThat(gapCodes(a)).contains("NO_PRIMARY_TARGET");
        // 主靶点为空时不应去查结构
        assertThat(gapCodes(a)).doesNotContain("NO_STRUCTURE");
    }

    @Test
    @DisplayName("结构存在但没有口袋：只告警，不影响 ready")
    void noPocketIsWarningOnly() {
        stubComplete();
        when(bindingPocketMapper.selectList(any())).thenReturn(Collections.emptyList());

        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("8"), 4));

        assertThat(a.isReady()).isTrue();
        assertThat(gapCodes(a)).contains("NO_POCKET");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.STRUCTURE.getCode(), "WARN");
    }

    @Test
    @DisplayName("约束集里只有软约束：HARD_RULES 仍 MISSING（软约束替代不了硬闸门）")
    void onlySoftRulesIsNotEnough() {
        stubComplete();
        ConstraintRule soft = new ConstraintRule();
        soft.setId(202L);
        soft.setSetId(CONSTRAINT_SET_ID);
        soft.setEnabled(1);
        soft.setSortNo(1);
        soft.setSeverity("SOFT");
        soft.setDimension("STABILITY");
        when(constraintRuleMapper.selectList(any())).thenReturn(List.of(soft));

        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("8"), 4));

        Map<String, String> state = stateOf(a);
        assertThat(state).containsEntry(BaselineBlock.HARD_RULES.getCode(), "MISSING")
                .containsEntry(BaselineBlock.SOFT_RULES.getCode(), "OK");
        assertThat(gapCodes(a)).contains("NO_HARD_RULE");
        assertThat(a.isReady()).isFalse();
    }

    @Test
    @DisplayName("分子形式字典项分类不对：MODALITY_INVALID，不回退到「随便挑一个」")
    void modalityWrongCategory() {
        stubComplete();
        DesignLibraryItem notModality = new DesignLibraryItem();
        notModality.setId(MODALITY_ID);
        notModality.setCode("X");
        notModality.setCategory("FORMAT");
        notModality.setEnabled(1);
        when(libraryItemMapper.selectById(MODALITY_ID)).thenReturn(notModality);

        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("8"), 4));

        assertThat(gapCodes(a)).contains("MODALITY_INVALID");
        assertThat(a.getModalityCode()).isNull();
    }

    @Test
    @DisplayName("配额：min_candidates 缺失是 ERROR，GPU 与湿实验缺失只是 WARN")
    void quotaRules() {
        stubComplete();
        BaselineAssembler.Assembled missing = assembler.assemble(project(null, null, null));
        assertThat(gapCodes(missing)).contains("NO_MIN_CANDIDATES", "QUOTA_GPU_UNLIMITED",
                "QUOTA_WET_LAB_UNLIMITED");
        assertThat(stateOf(missing)).containsEntry(BaselineBlock.QUOTA.getCode(), "MISSING");
        assertThat(missing.isReady()).isFalse();

        BaselineAssembler.Assembled warnOnly = assembler.assemble(project(5, null, null));
        assertThat(stateOf(warnOnly)).containsEntry(BaselineBlock.QUOTA.getCode(), "WARN");
        assertThat(warnOnly.isReady()).isTrue();
    }

    @Test
    @DisplayName("配额取负：GPU ≤ 0 与湿实验 < 0 都判 ERROR")
    void negativeQuotaIsError() {
        stubComplete();
        BaselineAssembler.Assembled a = assembler.assemble(project(5, new BigDecimal("0"), -1));

        assertThat(gapCodes(a)).contains("QUOTA_GPU_INVALID", "QUOTA_WET_LAB_INVALID");
        assertThat(a.isReady()).isFalse();
    }

    @Test
    @DisplayName("指标码不在字典：保留指标但告警，不静默丢弃")
    void unknownMetricCodeWarns() {
        stubComplete();
        MetricDefinition other = new MetricDefinition();
        other.setId(2L);
        other.setMetricCode("STABILITY");
        other.setEnabled(1);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(other));

        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("8"), 4));

        assertThat(gapCodes(a)).contains("METRIC_UNKNOWN");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.METRICS.getCode(), "WARN");
        // 指标本身仍在快照里，评估时才会被跳过
        assertThat(a.getMetricsJson()).contains("AFFINITY");
    }

    @Test
    @DisplayName("有风险点但都没标 mustVerify：告警，提示决策阶段不会强制验证")
    void riskWithoutMustVerifyWarns() {
        stubComplete();
        ProjectRiskItem risk = new ProjectRiskItem();
        risk.setId(401L);
        risk.setProjectId(PROJECT);
        risk.setRiskCode("PTM_HOTSPOT");
        risk.setSeverity("MEDIUM");
        risk.setMustVerify(0);
        when(projectRiskItemMapper.selectList(any())).thenReturn(List.of(risk));

        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("8"), 4));

        assertThat(gapCodes(a)).contains("NO_MUST_VERIFY_RISK");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.RISKS.getCode(), "WARN");
        assertThat(a.isReady()).isTrue();
    }

    @Test
    @DisplayName("未指定作用机制：只告警（NO_MOA），不堵冻结")
    void missingMoaWarnsOnly() {
        stubComplete();
        DesignProject p = project(20, new BigDecimal("8"), 4);
        p.setMoaCode(null);

        BaselineAssembler.Assembled a = assembler.assemble(p);

        assertThat(gapCodes(a)).contains("NO_MOA");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.TARGETS.getCode(), "WARN");
        assertThat(a.isReady()).isTrue();
        // moaCode 必须真的进快照，否则生成阶段拿不到机制
        assertThat(a.getTargetsJson()).contains("moaCode");
    }

    @Test
    @DisplayName("指定了作用机制：写进 TARGETS 块，且无 NO_MOA 告警")
    void moaGoesIntoTargetsBlock() {
        stubComplete();

        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("8"), 4));

        assertThat(gapCodes(a)).doesNotContain("NO_MOA");
        assertThat(a.getTargetsJson()).contains("\"moaCode\":\"INHIBITOR\"");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.TARGETS.getCode(), "OK");
    }

    @Test
    @DisplayName("决策名额档位：RATIO 0.2 通过，写进 QUOTA 块摘要")
    void capacityRatioOk() {
        stubComplete();
        BaselineAssembler.Assembled a = assembler.assemble(project(20, new BigDecimal("8"), 4));

        assertThat(gapCodes(a)).doesNotContain("NO_CAPACITY_MODE", "CAPACITY_MODE_INVALID",
                "CAPACITY_VALUE_INVALID", "CAPACITY_RATIO_OUT_OF_RANGE");
        assertThat(a.getQuotaJson()).contains("\"capacityMode\":\"RATIO\"");
        assertThat(a.getQuotaJson()).contains("0.2");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.QUOTA.getCode(), "OK");
    }

    @Test
    @DisplayName("RATIO 模式填 20（把比例当百分数写）：报超范围，而不是静默按 2000% 算")
    void capacityRatioOutOfRange() {
        stubComplete();
        DesignProject p = project(20, new BigDecimal("8"), 4);
        p.setDecisionCapacityMode("RATIO");
        p.setDecisionCapacityValue(new BigDecimal("20"));

        BaselineAssembler.Assembled a = assembler.assemble(p);

        assertThat(gapCodes(a)).contains("CAPACITY_RATIO_OUT_OF_RANGE");
        assertThat(stateOf(a)).containsEntry(BaselineBlock.QUOTA.getCode(), "MISSING");
        assertThat(a.isReady()).isFalse();
    }

    @Test
    @DisplayName("ABSOLUTE 模式：整数通过，小数被拒（避免 2.5 个候选这种无意义值）")
    void capacityAbsoluteMustBeInteger() {
        stubComplete();
        DesignProject ok = project(20, new BigDecimal("8"), 4);
        ok.setDecisionCapacityMode("ABSOLUTE");
        ok.setDecisionCapacityValue(new BigDecimal("8"));
        assertThat(gapCodes(assembler.assemble(ok))).doesNotContain("CAPACITY_ABSOLUTE_NOT_INTEGER");

        DesignProject bad = project(20, new BigDecimal("8"), 4);
        bad.setDecisionCapacityMode("ABSOLUTE");
        bad.setDecisionCapacityValue(new BigDecimal("2.5"));
        assertThat(gapCodes(assembler.assemble(bad))).contains("CAPACITY_ABSOLUTE_NOT_INTEGER");
    }

    @Test
    @DisplayName("档位为空或非法：报缺失/非法，不猜默认值")
    void capacityModeRequired() {
        stubComplete();
        DesignProject blank = project(20, new BigDecimal("8"), 4);
        blank.setDecisionCapacityMode(null);
        assertThat(gapCodes(assembler.assemble(blank))).contains("NO_CAPACITY_MODE");

        DesignProject weird = project(20, new BigDecimal("8"), 4);
        weird.setDecisionCapacityMode("PERCENT");
        assertThat(gapCodes(assembler.assemble(weird))).contains("CAPACITY_MODE_INVALID");
    }

    @Test
    @DisplayName("档位参数 ≤ 0：报非法")
    void capacityValueMustBePositive() {
        stubComplete();
        DesignProject p = project(20, new BigDecimal("8"), 4);
        p.setDecisionCapacityValue(BigDecimal.ZERO);

        assertThat(gapCodes(assembler.assemble(p))).contains("CAPACITY_VALUE_INVALID");
    }

    @Test
    @DisplayName("档位变更：contentHash 变化（否则改了推荐规模也冻结不出新版本）")
    void capacityChangeAffectsHash() {
        stubComplete();
        String before = assembler.assemble(project(20, new BigDecimal("8"), 4)).getContentHash();

        DesignProject changed = project(20, new BigDecimal("8"), 4);
        changed.setDecisionCapacityMode("ABSOLUTE");
        changed.setDecisionCapacityValue(new BigDecimal("5"));
        String after = assembler.assemble(changed).getContentHash();

        assertThat(after).isNotEqualTo(before);
    }

    // ------------------------------------------------------------------ 哈希

    @Test
    @DisplayName("同内容重复装配：contentHash 完全一致（冻结幂等的前提）")
    void hashIsStableForSameContent() {
        stubComplete();
        String first = assembler.assemble(project(20, new BigDecimal("48"), 8)).getContentHash();
        String second = assembler.assemble(project(20, new BigDecimal("48"), 8)).getContentHash();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("指标权重变更：contentHash 变化（否则改了配置也冻结不出新版本）")
    void hashChangesWhenContentChanges() {
        stubComplete();
        String before = assembler.assemble(project(20, new BigDecimal("48"), 8)).getContentHash();

        ProjectMetric changed = new ProjectMetric();
        changed.setId(301L);
        changed.setProjectId(PROJECT);
        changed.setEnabled(1);
        changed.setPriority(1);
        changed.setMetricCode("AFFINITY");
        changed.setWeight(new BigDecimal("0.7"));
        when(projectMetricMapper.selectList(any())).thenReturn(List.of(changed));

        String after = assembler.assemble(project(20, new BigDecimal("48"), 8)).getContentHash();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("配额变更：contentHash 变化")
    void hashChangesWhenQuotaChanges() {
        stubComplete();
        String before = assembler.assemble(project(20, new BigDecimal("48"), 8)).getContentHash();
        String after = assembler.assemble(project(30, new BigDecimal("48"), 8)).getContentHash();

        assertThat(after).isNotEqualTo(before);
    }
}
