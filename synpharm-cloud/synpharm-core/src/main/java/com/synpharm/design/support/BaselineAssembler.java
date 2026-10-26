package com.synpharm.design.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.BaselinePreviewResponse;
import com.synpharm.design.domain.model.BaselineBlock;
import com.synpharm.design.domain.model.BindingPocket;
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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求基线的装配器（06 文档 §3.2）。
 *
 * <p>把散在 10 张表里的项目定义拼成<b>八块</b>，逐块判定完整性，并算出一个内容哈希。
 * 生成任务 S0 只读快照、不读项目当前状态 —— 所以这里的产物必须自足：
 * 八块 JSON + 内容哈希就是「这一刻项目到底想做什么」的完整答案。
 *
 * <p><b>为什么哈希只覆盖八块内容、不含时间与版本号</b>：
 * 冻结是幂等操作，同样内容重复冻结必须命中同一快照（{@code freeze} 靠哈希去重）。
 * 版本号变了但业务内容没变，说明用户只是点了「升版本」却没改东西，不该产生新快照。
 *
 * @author SynPharm Team
 */
@Component
@RequiredArgsConstructor
public class BaselineAssembler {

    private final ProjectTargetMapper projectTargetMapper;
    private final TargetMapper targetMapper;
    private final TargetStructureMapper targetStructureMapper;
    private final BindingPocketMapper bindingPocketMapper;
    private final ConstraintSetMapper constraintSetMapper;
    private final ConstraintRuleMapper constraintRuleMapper;
    private final ProjectMetricMapper projectMetricMapper;
    private final ProjectRiskItemMapper projectRiskItemMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DesignLibraryItemMapper libraryItemMapper;
    private final JsonCodec jsonCodec;

    /**
     * 装配八块。
     *
     * <p>只读，不写库；调用方决定是「预检」（丢弃结果）还是「冻结」（落库）。
     */
    public Assembled assemble(DesignProject project) {
        List<ProjectTarget> bindings = loadBindings(project.getId());
        Long primaryTargetId = primaryTargetIdOf(bindings);
        Target primaryTarget = primaryTargetId == null ? null : targetMapper.selectById(primaryTargetId);

        BlockResult targets = buildTargets(bindings, primaryTarget, project.getMoaCode());
        BlockResult structure = buildStructure(primaryTarget);
        BlockResult modality = buildModality(project.getModalityId());
        RulesBundle rules = buildRules(project.getActiveConstraintSetId());
        BlockResult metrics = buildMetrics(project.getId());
        BlockResult quota = buildQuota(project);
        BlockResult risks = buildRisks(project.getId());

        List<BlockResult> all = List.of(
                targets, structure, modality,
                rules.hard(), rules.soft(),
                metrics, quota, risks);

        List<BaselinePreviewResponse.BlockStatus> blocks = new ArrayList<>(all.size());
        List<BaselinePreviewResponse.Gap> gaps = new ArrayList<>();
        Map<String, String> completeness = new LinkedHashMap<>();
        StringBuilder hashSource = new StringBuilder(1024);

        for (BlockResult r : all) {
            blocks.add(r.toStatus());
            gaps.addAll(r.getGaps());
            completeness.put(r.getBlock().getCode(), r.state());
            // \u0000 做分隔符：块内可能含换行（humanReadable），用换行会歧义
            hashSource.append(jsonCodec.write(r.getPayload())).append('\u0000');
        }

        boolean ready = gaps.stream().noneMatch(g -> "ERROR".equals(g.getSeverity()));

        BaselinePreviewResponse preview = new BaselinePreviewResponse();
        preview.setReady(ready);
        preview.setBlocks(blocks);
        preview.setGaps(gaps);

        Assembled assembled = new Assembled(
                jsonCodec.write(targets.getPayload()),
                jsonCodec.write(structure.getPayload()),
                modality.codeOrNull(),
                jsonCodec.write(rules.hard().getPayload()),
                jsonCodec.write(rules.soft().getPayload()),
                jsonCodec.write(metrics.getPayload()),
                jsonCodec.write(quota.getPayload()),
                jsonCodec.write(risks.getPayload()),
                jsonCodec.write(completeness));
        assembled.contentHash = sha256(hashSource.toString());
        assembled.preview = preview;
        preview.setContentHash(assembled.contentHash);
        return assembled;
    }

    // ------------------------------------------------------------------ 块1 靶点

    private List<ProjectTarget> loadBindings(Long projectId) {
        return projectTargetMapper.selectList(
                Wrappers.lambdaQuery(ProjectTarget.class)
                        .eq(ProjectTarget::getProjectId, projectId)
                        .orderByDesc(ProjectTarget::getIsPrimary)
                        .orderByAsc(ProjectTarget::getSortNo));
    }

    /** 主靶点只认关联行上的 isPrimary=1；历史数据里 Target 侧也可能有标记，不采信。 */
    private Long primaryTargetIdOf(List<ProjectTarget> bindings) {
        return bindings.stream()
                .filter(b -> Integer.valueOf(1).equals(b.getIsPrimary()))
                .map(ProjectTarget::getTargetId)
                .findFirst()
                .orElse(null);
    }

    private BlockResult buildTargets(List<ProjectTarget> bindings, Target primary, String moaCode) {
        BlockResult r = new BlockResult(BaselineBlock.TARGETS);
        r.put("primaryTargetId", primary == null ? null : primary.getId());
        r.put("moaCode", moaCode);

        if (bindings.isEmpty()) {
            r.put("items", Collections.emptyList());
            r.error("NO_TARGET", "项目还没有绑定任何靶点，先去「靶点」步骤添加");
            return r;
        }

        Set<Long> ids = bindings.stream()
                .map(ProjectTarget::getTargetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Target> targetById = targetMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Target::getId, t -> t, (a, b) -> a));

        List<Map<String, Object>> items = new ArrayList<>(bindings.size());
        for (ProjectTarget b : bindings) {
            Target t = targetById.get(b.getTargetId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("targetId", b.getTargetId());
            item.put("role", b.getRole());
            item.put("isPrimary", Integer.valueOf(1).equals(b.getIsPrimary()));
            item.put("name", t == null ? null : t.getName());
            item.put("uniprotId", t == null ? null : t.getUniprotId());
            item.put("targetType", t == null ? null : t.getTargetType());
            items.add(item);
        }
        r.put("items", items);

        if (primary == null) {
            r.error("NO_PRIMARY_TARGET", "已绑定 " + bindings.size() + " 个靶点但没有指定主靶点，结构准备无法确定对象");
            return r;
        }
        r.summary(bindings.size() + " 个靶点，主靶点 " + primary.getName());
        if (!StringUtils.hasText(primary.getUniprotId())) {
            r.warn("PRIMARY_TARGET_NO_UNIPROT", "主靶点缺少 UniProt 编号，后续靶点校验与同源性分析会受限");
        }
        if (!StringUtils.hasText(moaCode)) {
            r.warn("NO_MOA", "未指定作用机制，生成策略只能按分子形式兜底选择，策略模板可能不匹配");
        }
        return r;
    }

    // ------------------------------------------------------------------ 块2 结构

    private BlockResult buildStructure(Target primary) {
        BlockResult r = new BlockResult(BaselineBlock.STRUCTURE);
        if (primary == null) {
            r.put("structures", Collections.emptyList());
            r.put("pockets", Collections.emptyList());
            r.error("NO_PRIMARY_TARGET", "没有主靶点，无法确定用哪个靶点的结构");
            return r;
        }

        List<TargetStructure> structures = targetStructureMapper.selectList(
                Wrappers.lambdaQuery(TargetStructure.class)
                        .eq(TargetStructure::getTargetId, primary.getId())
                        .orderByDesc(TargetStructure::getIsPreferred)
                        .orderByDesc(TargetStructure::getId));
        r.put("targetId", primary.getId());

        if (structures.isEmpty()) {
            r.put("structures", Collections.emptyList());
            r.put("pockets", Collections.emptyList());
            r.error("NO_STRUCTURE", "主靶点还没有导入任何结构（PDB 或预测模型）");
            return r;
        }

        List<Map<String, Object>> sItems = new ArrayList<>(structures.size());
        List<Long> structureIds = new ArrayList<>(structures.size());
        for (TargetStructure s : structures) {
            structureIds.add(s.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("structureId", s.getId());
            item.put("pdbId", s.getPdbId());
            item.put("chainId", s.getChainId());
            item.put("source", s.getSource());
            item.put("experimentMethod", s.getExperimentMethod());
            item.put("resolution", s.getResolution());
            item.put("fileAssetId", s.getFileAssetId());
            item.put("isPreferred", Integer.valueOf(1).equals(s.getIsPreferred()));
            sItems.add(item);
        }
        r.put("structures", sItems);

        List<BindingPocket> pockets = bindingPocketMapper.selectList(
                Wrappers.lambdaQuery(BindingPocket.class)
                        .in(BindingPocket::getStructureId, structureIds)
                        .orderByDesc(BindingPocket::getIsSelected)
                        .orderByAsc(BindingPocket::getPocketNo));
        List<Map<String, Object>> pItems = new ArrayList<>(pockets.size());
        for (BindingPocket p : pockets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pocketId", p.getId());
            item.put("structureId", p.getStructureId());
            item.put("pocketNo", p.getPocketNo());
            item.put("method", p.getMethod());
            item.put("score", p.getScore());
            item.put("druggability", p.getDruggability());
            item.put("isSelected", Integer.valueOf(1).equals(p.getIsSelected()));
            pItems.add(item);
        }
        r.put("pockets", pItems);

        r.summary(structures.size() + " 个结构，" + pockets.size() + " 个口袋");
        if (structures.stream().noneMatch(s -> Integer.valueOf(1).equals(s.getIsPreferred()))) {
            r.warn("NO_PREFERRED_STRUCTURE", "没有标记首选结构，S1 结构准备会按最新一条处理");
        }
        if (pockets.isEmpty()) {
            r.warn("NO_POCKET", "尚未检测口袋；依赖口袋的策略（如定向对接）届时无法启用");
        }
        return r;
    }

    // ------------------------------------------------------------------ 块3 分子形式

    private BlockResult buildModality(Long modalityId) {
        BlockResult r = new BlockResult(BaselineBlock.MODALITY);
        if (modalityId == null) {
            r.code = null;
            r.error("NO_MODALITY", "未选择分子形式（单抗 / 双抗 / 纳米抗体 / 多肽 / 小分子…）");
            return r;
        }
        DesignLibraryItem item = libraryItemMapper.selectById(modalityId);
        if (item == null || !"MODALITY".equals(item.getCategory()) || !Integer.valueOf(1).equals(item.getEnabled())) {
            r.error("MODALITY_INVALID", "分子形式字典项（#" + modalityId + "）不存在、已停用或不是 MODALITY 分类");
            return r;
        }
        r.code = item.getCode();
        r.put("modalityId", item.getId());
        r.put("code", item.getCode());
        r.put("name", item.getName());

        Map<String, Object> meta = StringUtils.hasText(item.getMetaJson())
                ? jsonCodec.readMap(item.getMetaJson())
                : Collections.emptyMap();
        boolean seqRequired = !Boolean.FALSE.equals(meta.get("seqRequired"));
        r.put("seqRequired", seqRequired);
        r.put("defaultDimensions", meta.get("defaultDimensions"));
        r.put("defaultEditStrategies", meta.get("defaultEditStrategies"));

        r.summary(item.getName());
        if (meta.get("defaultDimensions") == null) {
            r.warn("MODALITY_NO_DIMENSIONS", "该分子形式没有配置默认评估维度，指标步骤需要手工挑选");
        }
        return r;
    }

    // ------------------------------------------------------------------ 块4/5 约束

    private RulesBundle buildRules(Long constraintSetId) {
        BlockResult hard = new BlockResult(BaselineBlock.HARD_RULES);
        BlockResult soft = new BlockResult(BaselineBlock.SOFT_RULES);
        hard.put("items", Collections.emptyList());
        soft.put("items", Collections.emptyList());

        if (constraintSetId == null) {
            hard.error("NO_CONSTRAINT_SET", "未绑定生效约束集，生成阶段无法过滤越界候选");
            return new RulesBundle(hard, soft);
        }
        ConstraintSet set = constraintSetMapper.selectById(constraintSetId);
        if (set == null) {
            hard.error("CONSTRAINT_SET_MISSING", "生效约束集（#" + constraintSetId + "）已不存在，请重新选择");
            return new RulesBundle(hard, soft);
        }

        hard.put("constraintSetId", set.getId());
        hard.put("setNo", set.getSetNo());
        hard.put("logic", set.getLogic());
        soft.put("constraintSetId", set.getId());
        soft.put("setNo", set.getSetNo());
        soft.put("logic", set.getLogic());

        List<ConstraintRule> rules = constraintRuleMapper.selectList(
                Wrappers.lambdaQuery(ConstraintRule.class)
                        .eq(ConstraintRule::getSetId, constraintSetId)
                        .eq(ConstraintRule::getEnabled, 1)
                        .orderByAsc(ConstraintRule::getSortNo));

        List<Map<String, Object>> hardItems = new ArrayList<>();
        List<Map<String, Object>> softItems = new ArrayList<>();
        for (ConstraintRule rule : rules) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleId", rule.getId());
            item.put("ruleType", rule.getRuleType());
            item.put("dimension", rule.getDimension());
            item.put("operator", rule.getOperator());
            item.put("thresholdNum", rule.getThresholdNum());
            item.put("thresholdMax", rule.getThresholdMax());
            item.put("tolerance", rule.getTolerance());
            item.put("boundaryPolicy", rule.getBoundaryPolicy());
            item.put("unit", rule.getUnit());
            item.put("weight", rule.getWeight());
            item.put("appliesToModality", rule.getAppliesToModality());
            item.put("paramsJson", rule.getParamsJson());
            item.put("humanReadable", rule.getHumanReadable());
            if ("SOFT".equalsIgnoreCase(rule.getSeverity())) {
                softItems.add(item);
            } else {
                hardItems.add(item);
            }
        }
        hard.put("items", hardItems);
        soft.put("items", softItems);

        if (hardItems.isEmpty()) {
            hard.error("NO_HARD_RULE", "生效约束集「" + set.getName() + "」里没有启用任何硬约束（severity=HARD）");
        } else {
            hard.summary(hardItems.size() + " 条硬约束");
        }
        if (softItems.isEmpty()) {
            soft.summary("无软约束");
        } else {
            soft.summary(softItems.size() + " 条软约束");
        }
        if (!"ACTIVE".equalsIgnoreCase(set.getStatus())) {
            hard.warn("CONSTRAINT_SET_NOT_ACTIVE", "约束集当前状态是 " + set.getStatus() + "，冻结后仍按此版本执行");
        }
        return new RulesBundle(hard, soft);
    }

    // ------------------------------------------------------------------ 块6 指标

    private BlockResult buildMetrics(Long projectId) {
        BlockResult r = new BlockResult(BaselineBlock.METRICS);
        List<ProjectMetric> metrics = projectMetricMapper.selectList(
                Wrappers.lambdaQuery(ProjectMetric.class)
                        .eq(ProjectMetric::getProjectId, projectId)
                        .eq(ProjectMetric::getEnabled, 1)
                        .orderByAsc(ProjectMetric::getPriority)
                        .orderByAsc(ProjectMetric::getId));

        if (metrics.isEmpty()) {
            r.put("items", Collections.emptyList());
            r.error("NO_METRIC", "项目未启用任何评估指标，后续评估环节无维度可算");
            return r;
        }

        List<MetricDefinition> definitions = metricDefinitionMapper.selectList(
                Wrappers.lambdaQuery(MetricDefinition.class).eq(MetricDefinition::getEnabled, 1));
        Map<String, MetricDefinition> defByCode = definitions.stream()
                .collect(Collectors.toMap(MetricDefinition::getMetricCode, d -> d, (a, b) -> a));

        List<Map<String, Object>> items = new ArrayList<>(metrics.size());
        List<String> unknown = new ArrayList<>();
        for (ProjectMetric m : metrics) {
            MetricDefinition def = defByCode.get(m.getMetricCode());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metricCode", m.getMetricCode());
            item.put("dimension", m.getDimension() != null ? m.getDimension()
                    : (def == null ? null : def.getDimension()));
            item.put("metricName", def == null ? null : def.getMetricName());
            item.put("unit", def == null ? null : def.getUnit());
            item.put("better", def == null ? null : def.getBetter());
            item.put("weight", m.getWeight());
            item.put("targetValue", m.getTargetValue());
            item.put("threshold", m.getThreshold());
            item.put("operator", m.getOperator());
            item.put("tolerance", m.getTolerance());
            item.put("boundaryPolicy", m.getBoundaryPolicy());
            item.put("priority", m.getPriority());
            items.add(item);
            if (def == null) {
                unknown.add(m.getMetricCode());
            }
        }
        r.put("items", items);
        r.summary(items.size() + " 个指标");
        if (!unknown.isEmpty()) {
            r.warn("METRIC_UNKNOWN", "指标码 " + String.join("、", unknown)
                    + " 在指标字典里不存在或已停用，评估时会被跳过");
        }
        return r;
    }

    // ------------------------------------------------------------------ 块7 配额

    private BlockResult buildQuota(DesignProject project) {
        BlockResult r = new BlockResult(BaselineBlock.QUOTA);
        Integer minCandidates = project.getMinCandidates();
        BigDecimal gpuHours = project.getQuotaGpuHours();
        Integer wetLab = project.getQuotaWetLab();
        String capacityMode = project.getDecisionCapacityMode();
        BigDecimal capacityValue = project.getDecisionCapacityValue();

        r.put("minCandidates", minCandidates);
        r.put("gpuHours", gpuHours);
        r.put("wetLab", wetLab);
        r.put("capacityMode", capacityMode);
        r.put("capacityValue", capacityValue);

        if (minCandidates == null || minCandidates < 1) {
            r.error("NO_MIN_CANDIDATES", "最少候选数（min_candidates）必须 ≥ 1，否则生成后置闸门无从判定");
        }
        if (gpuHours != null && gpuHours.signum() <= 0) {
            r.error("QUOTA_GPU_INVALID", "GPU 小时配额为 " + gpuHours.toPlainString() + "，必须 > 0；不限额请留空");
        }
        if (wetLab != null && wetLab < 0) {
            r.error("QUOTA_WET_LAB_INVALID", "湿实验名额不能为负数");
        }
        validateCapacity(r, capacityMode, capacityValue);

        r.summary("最少 " + (minCandidates == null ? "—" : minCandidates) + " 个候选，"
                + (gpuHours == null ? "GPU 不限" : ("GPU " + gpuHours.toPlainString() + " h"))
                + (wetLab == null ? "，湿实验不限" : ("，湿实验 " + wetLab + " 个"))
                + "，决策名额 " + describeCapacity(capacityMode, capacityValue));

        if (gpuHours == null) {
            r.warn("QUOTA_GPU_UNLIMITED", "GPU 小时未设上限，S4 熔断无依据，长任务可能失控");
        }
        if (wetLab == null) {
            r.warn("QUOTA_WET_LAB_UNLIMITED", "湿实验名额未设上限，决策阶段无法按预算裁剪");
        }
        return r;
    }

    /**
     * 校验决策名额档位。
     *
     * <p>两个模式共用一列参数，所以必须按模式分别校验 —— 只判「> 0」会让
     * RATIO 模式接受 20（= 2000%）这种明显写错的值，决策阶段就会推荐出
     * 比候选总数还多的名额。
     */
    private void validateCapacity(BlockResult r, String mode, BigDecimal value) {
        if (!StringUtils.hasText(mode)) {
            r.error("NO_CAPACITY_MODE", "未选择决策名额档位（RATIO 按比例 / ABSOLUTE 固定个数）");
            return;
        }
        String normalized = mode.trim().toUpperCase();
        if (!"RATIO".equals(normalized) && !"ABSOLUTE".equals(normalized)) {
            r.error("CAPACITY_MODE_INVALID", "决策名额档位「" + mode + "」无法识别，只能是 RATIO 或 ABSOLUTE");
            return;
        }
        if (value == null || value.signum() <= 0) {
            r.error("CAPACITY_VALUE_INVALID", "决策名额参数必须 > 0，当前 " + (value == null ? "空" : value.toPlainString()));
            return;
        }
        if ("RATIO".equals(normalized) && value.compareTo(BigDecimal.ONE) > 0) {
            r.error("CAPACITY_RATIO_OUT_OF_RANGE",
                    "按比例模式的名额参数是 0~1 之间的小数（0.2 = 20%），当前 " + value.toPlainString()
                            + "；若想固定个数请把档位改成 ABSOLUTE");
            return;
        }
        if ("ABSOLUTE".equals(normalized) && value.stripTrailingZeros().scale() > 0) {
            r.error("CAPACITY_ABSOLUTE_NOT_INTEGER",
                    "固定个数模式的名额参数必须是整数，当前 " + value.toPlainString());
            return;
        }
        if ("ABSOLUTE".equals(normalized) && value.compareTo(BigDecimal.valueOf(1000)) > 0) {
            r.warn("CAPACITY_ABSOLUTE_HUGE", "固定推荐 " + value.toPlainString() + " 个候选，请确认不是把比例当成个数填写");
        }
    }

    private String describeCapacity(String mode, BigDecimal value) {
        if (!StringUtils.hasText(mode) || value == null) {
            return "未定";
        }
        if ("ABSOLUTE".equalsIgnoreCase(mode.trim())) {
            return "固定 " + value.stripTrailingZeros().toPlainString() + " 个";
        }
        return "按候选数 " + value.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    // ------------------------------------------------------------------ 块8 风险

    private BlockResult buildRisks(Long projectId) {
        BlockResult r = new BlockResult(BaselineBlock.RISKS);
        List<ProjectRiskItem> risks = projectRiskItemMapper.selectList(
                Wrappers.lambdaQuery(ProjectRiskItem.class)
                        .eq(ProjectRiskItem::getProjectId, projectId)
                        .orderByAsc(ProjectRiskItem::getId));
        List<Map<String, Object>> items = new ArrayList<>(risks.size());
        for (ProjectRiskItem risk : risks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("riskCode", risk.getRiskCode());
            item.put("dimension", risk.getDimension());
            item.put("description", risk.getDescription());
            item.put("severity", risk.getSeverity());
            item.put("mustVerify", Integer.valueOf(1).equals(risk.getMustVerify()));
            item.put("source", risk.getSource());
            items.add(item);
        }
        r.put("items", items);
        r.summary(items.isEmpty() ? "无风险关注点" : (items.size() + " 条风险关注点"));
        if (!items.isEmpty() && risks.stream().noneMatch(x -> Integer.valueOf(1).equals(x.getMustVerify()))) {
            r.warn("NO_MUST_VERIFY_RISK", "有风险点但都没标记「必须验证」，决策阶段不会强制要求实验确认");
        }
        return r;
    }

    // ------------------------------------------------------------------ 内部类型

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /** 八块装配结果，直接对应 {@code baseline_snapshot} 的列。 */
    @Getter
    public static class Assembled {
        private final String targetsJson;
        private final String structureJson;
        private final String modalityCode;
        private final String hardRulesJson;
        private final String softRulesJson;
        private final String metricsJson;
        private final String quotaJson;
        private final String risksJson;
        private final String completenessJson;
        private String contentHash;
        private BaselinePreviewResponse preview;

        Assembled(String targetsJson, String structureJson, String modalityCode,
                  String hardRulesJson, String softRulesJson, String metricsJson,
                  String quotaJson, String risksJson, String completenessJson) {
            this.targetsJson = targetsJson;
            this.structureJson = structureJson;
            this.modalityCode = modalityCode;
            this.hardRulesJson = hardRulesJson;
            this.softRulesJson = softRulesJson;
            this.metricsJson = metricsJson;
            this.quotaJson = quotaJson;
            this.risksJson = risksJson;
            this.completenessJson = completenessJson;
        }

        public boolean isReady() {
            return preview != null && preview.isReady();
        }

        /** ERROR 级缺项数量，冻结失败时用于拼错误文案 */
        public long errorCount() {
            return preview == null ? 0
                    : preview.getGaps().stream().filter(g -> "ERROR".equals(g.getSeverity())).count();
        }
    }

    private record RulesBundle(BlockResult hard, BlockResult soft) {}

    /** 单块的装配中间态。 */
    @Getter
    private static class BlockResult {
        private final BaselineBlock block;
        private final Map<String, Object> payload = new LinkedHashMap<>();
        private final List<BaselinePreviewResponse.Gap> gaps = new ArrayList<>();
        private String code;
        private String summary;

        BlockResult(BaselineBlock block) {
            this.block = block;
        }

        void put(String key, Object value) {
            payload.put(key, value);
        }

        void error(String gapCode, String message) {
            gaps.add(BaselinePreviewResponse.Gap.error(block.getCode(), gapCode, message));
        }

        void warn(String gapCode, String message) {
            gaps.add(BaselinePreviewResponse.Gap.warn(block.getCode(), gapCode, message));
        }

        void summary(String text) {
            this.summary = text;
        }

        String codeOrNull() {
            return code;
        }

        String state() {
            if (gaps.stream().anyMatch(g -> "ERROR".equals(g.getSeverity()))) {
                return "MISSING";
            }
            return gaps.isEmpty() ? "OK" : "WARN";
        }

        BaselinePreviewResponse.BlockStatus toStatus() {
            BaselinePreviewResponse.BlockStatus s = new BaselinePreviewResponse.BlockStatus();
            s.setCode(block.getCode());
            s.setLabel(block.getLabel());
            s.setRequired(block.isRequired());
            s.setState(state());
            String firstError = gaps.stream()
                    .filter(g -> "ERROR".equals(g.getSeverity()))
                    .map(BaselinePreviewResponse.Gap::getMessage)
                    .findFirst()
                    .orElse(null);
            String firstWarn = gaps.stream()
                    .filter(g -> "WARN".equals(g.getSeverity()))
                    .map(BaselinePreviewResponse.Gap::getMessage)
                    .findFirst()
                    .orElse(null);
            s.setMessage(firstError != null ? firstError : (firstWarn != null ? firstWarn : summary));
            return s;
        }
    }
}
