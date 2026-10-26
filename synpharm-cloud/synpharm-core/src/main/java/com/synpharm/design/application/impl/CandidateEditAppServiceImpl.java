package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.AbandonResponse;
import com.synpharm.design.api.dto.CandidateTreeResponse;
import com.synpharm.design.api.dto.EditGapResponse;
import com.synpharm.design.api.dto.EditImpactResponse;
import com.synpharm.design.application.CandidateEditAppService;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.EditActionMapping;
import com.synpharm.design.domain.model.EditGapReport;
import com.synpharm.design.domain.model.EditImpactAssessment;
import com.synpharm.design.domain.model.EvaluationFreeze;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.EditActionMappingMapper;
import com.synpharm.design.infrastructure.persistence.EditGapReportMapper;
import com.synpharm.design.infrastructure.persistence.EditImpactAssessmentMapper;
import com.synpharm.design.infrastructure.persistence.EvaluationFreezeMapper;
import com.synpharm.design.support.SerialNumbers;
import com.synpharm.design.support.editing.ActionMatcher;
import com.synpharm.design.support.editing.EditDtos;
import com.synpharm.design.support.editing.GapDimensionMapper;
import com.synpharm.design.support.editing.GapDiagnostician;
import com.synpharm.design.support.editing.ImpactAssessor;
import com.synpharm.design.support.evaluation.EvaluationDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编辑用例实现（E0~E3 + 版本树）。
 *
 * <p>所有前置条件都<b>显式拒绝</b>而不是给默认值。尤其是「没有冻结快照」：
 * 用当前分数做诊断看似方便，但同一候选在两次诊断之间改了阈值，
 * 结论就不可比，而 delta 报告正建立在可比性上。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateEditAppServiceImpl implements CandidateEditAppService {

    private final CandidateMoleculeMapper candidateMapper;
    private final DesignProjectMapper projectMapper;
    private final EvaluationFreezeMapper freezeMapper;
    private final EditGapReportMapper gapReportMapper;
    private final EditImpactAssessmentMapper impactMapper;
    private final EditActionMappingMapper actionMappingMapper;
    private final GapDiagnostician diagnostician;
    private final ActionMatcher actionMatcher;
    private final ImpactAssessor impactAssessor;
    private final JsonCodec jsonCodec;

    // ================================================================== E0+E1

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EditGapResponse diagnose(Long candidateId) {
        Long userId = CurrentUser.requireUserId();
        CandidateMolecule candidate = loadOwnedCandidate(candidateId, userId);

        // ---- E0 准入（5 项）----
        if ("abandoned".equalsIgnoreCase(candidate.getBranchStatus())) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "该分支已被放弃，不再参与编辑；如需继续请从父版本新建分支",
                    Map.of("candidateId", candidateId));
        }
        if (candidate.getNeedsReEval() != null && candidate.getNeedsReEval() == 1) {
            throw new BizException(ErrorCode.STALE_OBJECT,
                    "约束变更后该候选需要重新评估；用旧快照做缺口诊断会得出误导性结论",
                    Map.of("candidateId", candidateId, "hint", "先调用 evaluation:freeze 重新冻结"));
        }
        int versionNo = candidate.getVersionNo() == null ? 1 : candidate.getVersionNo();
        EvaluationFreeze freeze = freezeMapper.selectOne(Wrappers.lambdaQuery(EvaluationFreeze.class)
                .eq(EvaluationFreeze::getCandidateId, candidateId)
                .eq(EvaluationFreeze::getVersionNo, versionNo)
                .orderByDesc(EvaluationFreeze::getId)
                .last("LIMIT 1"));
        if (freeze == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "该候选还没有评估冻结快照；编辑的输入契约是冻结快照，请先评估",
                    Map.of("candidateId", candidateId, "hint", "POST /candidates/{id}/evaluation:freeze"));
        }

        // ---- E1 诊断 ----
        Map<String, EvaluationDtos.MetricScore> scores = parseScores(freeze.getScoresJson());
        EditDtos.Diagnosis diagnosis = diagnostician.diagnose(scores);

        EditGapReport report = new EditGapReport();
        report.setReportNo(SerialNumbers.editGapReportNo());
        report.setProjectId(candidate.getProjectId());
        report.setCandidateId(candidateId);
        report.setVersionNo(versionNo);
        report.setEvalFreezeId(freeze.getId());
        report.setGapsJson(jsonCodec.write(mapList(diagnosis.gaps())));
        report.setNoGapJson(jsonCodec.write(diagnosis.noGaps()));
        report.setUnfixableJson(jsonCodec.write(mapList(diagnosis.unfixable())));
        report.setRecommendedOrder(jsonCodec.write(diagnosis.recommendedOrder()));
        gapReportMapper.insert(report);

        // ---- E2 匹配动作 ----
        List<EditActionMapping> mappings = actionMappingMapper.selectList(
                Wrappers.lambdaQuery(EditActionMapping.class)
                        .eq(EditActionMapping::getEnabled, 1)
                        .eq(EditActionMapping::getDeleted, 0));
        List<EditDtos.ActionOption> options = actionMatcher.match(
                diagnosis.gaps(), mappings, candidate.getModalityCode());

        log.info("E1 诊断完成 candidate={} gaps={} noGaps={} unfixable={} actions={}",
                candidateId, diagnosis.gaps().size(), diagnosis.noGaps().size(),
                diagnosis.unfixable().size(), options.size());
        return toGapResponse(report, diagnosis, options);
    }

    @Override
    public EditGapResponse latestGaps(Long candidateId) {
        Long userId = CurrentUser.requireUserId();
        loadOwnedCandidate(candidateId, userId);
        EditGapReport report = gapReportMapper.selectOne(Wrappers.lambdaQuery(EditGapReport.class)
                .eq(EditGapReport::getCandidateId, candidateId)
                .orderByDesc(EditGapReport::getId)
                .last("LIMIT 1"));
        if (report == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "该候选还没有缺口诊断；请先调用 gaps:diagnose",
                    Map.of("candidateId", candidateId));
        }
        return toGapResponseFromStored(report);
    }

    // ================================================================== E3

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EditImpactResponse previewImpact(Long candidateId, String actionCode, String targetRegion) {
        Long userId = CurrentUser.requireUserId();
        CandidateMolecule candidate = loadOwnedCandidate(candidateId, userId);

        EditGapReport report = gapReportMapper.selectOne(Wrappers.lambdaQuery(EditGapReport.class)
                .eq(EditGapReport::getCandidateId, candidateId)
                .orderByDesc(EditGapReport::getId)
                .last("LIMIT 1"));
        if (report == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "E3 需要先有缺口诊断报告；请先调用 gaps:diagnose",
                    Map.of("candidateId", candidateId));
        }
        if (actionCode == null || actionCode.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "actionCode 不能为空");
        }

        List<String> noGaps = readStringList(report.getNoGapJson());
        Map<String, EvaluationDtos.MetricScore> scores = currentScores(candidateId, report.getVersionNo());

        EditDtos.ActionOption option = resolveOption(candidate, actionCode);
        EditDtos.Impact impact = option.sideEffects().isEmpty()
                ? impactAssessor.assessUnknown(option)
                : impactAssessor.assess(option, noGaps,
                        dim -> passedInDimension(scores, dim));

        EditImpactAssessment entity = new EditImpactAssessment();
        entity.setGapReportId(report.getId());
        entity.setCandidateId(candidateId);
        entity.setActionCode(impact.actionCode());
        entity.setTargetRegion(targetRegion);
        entity.setProtectedJson(jsonCodec.write(protectedMap(impact)));
        entity.setGuardResult(impact.guardResult());
        entity.setBlockReason(impact.blockReason());
        entity.setOverridden(0);
        impactMapper.insert(entity);

        return toImpactResponse(entity, impact);
    }

    // ================================================================== 版本树

    @Override
    public CandidateTreeResponse tree(Long candidateId) {
        Long userId = CurrentUser.requireUserId();
        CandidateMolecule focus = loadOwnedCandidate(candidateId, userId);

        // 向上找根
        List<CandidateMolecule> chainUp = new ArrayList<>();
        CandidateMolecule cursor = focus;
        Set<Long> guard = new LinkedHashSet<>();
        while (cursor != null && guard.add(cursor.getId())) {
            chainUp.add(cursor);
            Long parentId = cursor.getParentMolId();
            cursor = parentId == null ? null : candidateMapper.selectById(parentId);
        }
        CandidateMolecule root = chainUp.isEmpty() ? focus : chainUp.get(chainUp.size() - 1);

        // 向下收集整棵子树（广度优先，避免递归爆栈）
        List<CandidateMolecule> all = new ArrayList<>();
        List<CandidateMolecule> frontier = List.of(root);
        Set<Long> visited = new LinkedHashSet<>();
        while (!frontier.isEmpty()) {
            List<CandidateMolecule> next = new ArrayList<>();
            for (CandidateMolecule node : frontier) {
                if (!visited.add(node.getId())) {
                    continue;
                }
                all.add(node);
                List<CandidateMolecule> children = candidateMapper.selectList(
                        Wrappers.lambdaQuery(CandidateMolecule.class)
                                .eq(CandidateMolecule::getParentMolId, node.getId())
                                .eq(CandidateMolecule::getDeleted, 0));
                next.addAll(children);
            }
            frontier = next;
            // 上限保护：单棵树不超过 500 个节点
            if (visited.size() > 500) {
                log.warn("候选 {} 的版本树超过 500 节点，已截断", candidateId);
                break;
            }
        }

        Map<Long, CandidateMolecule> byId = new LinkedHashMap<>();
        for (CandidateMolecule m : all) {
            byId.put(m.getId(), m);
        }

        // 哪些节点有冻结快照
        Set<Long> withFreeze = new LinkedHashSet<>();
        if (!all.isEmpty()) {
            List<Long> ids = all.stream().map(CandidateMolecule::getId).toList();
            List<EvaluationFreeze> freezes = freezeMapper.selectList(
                    Wrappers.lambdaQuery(EvaluationFreeze.class).in(EvaluationFreeze::getCandidateId, ids));
            for (EvaluationFreeze f : freezes) {
                withFreeze.add(f.getCandidateId());
            }
        }

        CandidateTreeResponse resp = new CandidateTreeResponse();
        resp.setRootId(root.getId());
        resp.setFocusId(candidateId);
        List<CandidateTreeResponse.Node> nodes = new ArrayList<>();
        for (CandidateMolecule m : all) {
            nodes.add(toNode(m, candidateId, byId, withFreeze));
        }
        resp.setNodes(nodes);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AbandonResponse abandon(Long candidateId) {
        Long userId = CurrentUser.requireUserId();
        CandidateMolecule candidate = loadOwnedCandidate(candidateId, userId);
        if ("abandoned".equalsIgnoreCase(candidate.getBranchStatus())) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT, "该分支已经被放弃过");
        }
        candidate.setBranchStatus("abandoned");
        candidateMapper.updateById(candidate);
        log.info("候选 {} 分支已放弃（保留在树中）", candidateId);

        AbandonResponse resp = new AbandonResponse();
        resp.setCandidateId(candidateId);
        resp.setBranchStatus("abandoned");
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long revert(Long candidateId) {
        Long userId = CurrentUser.requireUserId();
        CandidateMolecule current = loadOwnedCandidate(candidateId, userId);
        if (current.getParentMolId() == null) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "根版本没有父版本可回退", Map.of("candidateId", candidateId));
        }
        CandidateMolecule parent = candidateMapper.selectById(current.getParentMolId());
        if (parent == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "父版本不存在：" + current.getParentMolId());
        }

        // 新建一个与父同内容的新版本：历史只增不删，
        // 被放弃的那条路径恰恰是回流沉淀的样本
        CandidateMolecule newNode = new CandidateMolecule();
        newNode.setProjectId(current.getProjectId());
        newNode.setRoundId(current.getRoundId());
        newNode.setOriginRunId(null);
        newNode.setOriginType("REVERT");
        newNode.setModalityCode(parent.getModalityCode());
        newNode.setSmiles(parent.getSmiles());
        newNode.setCanonicalSmiles(parent.getCanonicalSmiles());
        newNode.setSequence(parent.getSequence());
        newNode.setSequenceHash(parent.getSequenceHash());
        newNode.setSequenceLength(parent.getSequenceLength());
        newNode.setHeavyChain(parent.getHeavyChain());
        newNode.setLightChain(parent.getLightChain());
        newNode.setExtraChains(parent.getExtraChains());
        newNode.setMoleculeSubtype(parent.getMoleculeSubtype());
        newNode.setPipelineStatus("generated");
        newNode.setReviewStatus("NEW");
        newNode.setHardPassed(0);
        newNode.setParentMolId(parent.getId());
        newNode.setVersionNo((current.getVersionNo() == null ? 1 : current.getVersionNo()) + 1);
        newNode.setEditRound(current.getEditRound() == null ? 0 : current.getEditRound() + 1);
        newNode.setBranchStatus("active");
        newNode.setNeedsReEval(1);
        newNode.setStrategyName("REVERT");
        newNode.setNote("回退自候选 #" + candidateId + "（内容取自父版本 #" + parent.getId() + "）");
        candidateMapper.insert(newNode);

        log.info("候选 {} 回退：新建 #{}-v{}（内容同父 #{}）",
                candidateId, newNode.getId(), newNode.getVersionNo(), parent.getId());
        return newNode.getId();
    }

    // ================================================================== 组装

    private EditGapResponse toGapResponse(EditGapReport report, EditDtos.Diagnosis diagnosis,
                                          List<EditDtos.ActionOption> options) {
        EditGapResponse dto = new EditGapResponse();
        dto.setId(report.getId());
        dto.setReportNo(report.getReportNo());
        dto.setCandidateId(report.getCandidateId());
        dto.setVersionNo(report.getVersionNo());
        dto.setEvalFreezeId(report.getEvalFreezeId());
        dto.setGaps(mapList(diagnosis.gaps()));
        dto.setNoGaps(diagnosis.noGaps());
        dto.setUnfixable(mapList(diagnosis.unfixable()));
        dto.setRecommendedOrder(diagnosis.recommendedOrder());
        dto.setNote(diagnosis.note());
        List<Map<String, Object>> actionMaps = new ArrayList<>();
        for (EditDtos.ActionOption o : options) {
            actionMaps.add(o.toMap());
        }
        dto.setActions(actionMaps);
        return dto;
    }

    private EditGapResponse toGapResponseFromStored(EditGapReport report) {
        EditGapResponse dto = new EditGapResponse();
        dto.setId(report.getId());
        dto.setReportNo(report.getReportNo());
        dto.setCandidateId(report.getCandidateId());
        dto.setVersionNo(report.getVersionNo());
        dto.setEvalFreezeId(report.getEvalFreezeId());
        dto.setGaps(readMapList(report.getGapsJson()));
        dto.setNoGaps(readStringList(report.getNoGapJson()));
        dto.setUnfixable(readMapList(report.getUnfixableJson()));
        dto.setRecommendedOrder(readStringList(report.getRecommendedOrder()));
        return dto;
    }

    private EditImpactResponse toImpactResponse(EditImpactAssessment entity, EditDtos.Impact impact) {
        EditImpactResponse dto = new EditImpactResponse();
        dto.setId(entity.getId());
        dto.setCandidateId(entity.getCandidateId());
        dto.setActionCode(entity.getActionCode());
        dto.setGuardResult(entity.getGuardResult());
        dto.setBlockReason(entity.getBlockReason());
        List<Map<String, Object>> items = new ArrayList<>();
        for (EditDtos.ProtectedImpact p : impact.protectedOnes()) {
            items.add(p.toMap());
        }
        dto.setProtectedOnes(items);
        dto.setExecutable(!ImpactAssessor.BLOCK.equals(entity.getGuardResult()));
        return dto;
    }

    private Map<String, Object> protectedMap(EditDtos.Impact impact) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (EditDtos.ProtectedImpact p : impact.protectedOnes()) {
            list.add(p.toMap());
        }
        m.put("items", list);
        return m;
    }

    private CandidateTreeResponse.Node toNode(CandidateMolecule m, Long focusId,
                                              Map<Long, CandidateMolecule> byId,
                                              Set<Long> withFreeze) {
        CandidateTreeResponse.Node node = new CandidateTreeResponse.Node();
        node.setId(m.getId());
        node.setParentId(m.getParentMolId());
        node.setVersionNo(m.getVersionNo());
        node.setEditRound(m.getEditRound());
        node.setBranchStatus(m.getBranchStatus());
        node.setPipelineStatus(m.getPipelineStatus());
        node.setOriginType(m.getOriginType());
        node.setStrategyName(m.getStrategyName());
        node.setSmiles(m.getSmiles());
        node.setSequenceHash(m.getSequenceHash());
        node.setTotalScore(m.getTotalScore());
        node.setHardPassed(m.getHardPassed() != null && m.getHardPassed() == 1);
        node.setHasFreeze(withFreeze.contains(m.getId()));
        node.setNeedsReEval(m.getNeedsReEval());
        node.setIsFocus(m.getId().equals(focusId));
        node.setNote(m.getNote());
        node.setCreatedAt(m.getCreatedAt());
        node.setDepth(depthOf(m, byId));
        return node;
    }

    private int depthOf(CandidateMolecule m, Map<Long, CandidateMolecule> byId) {
        int depth = 0;
        Set<Long> guard = new LinkedHashSet<>();
        CandidateMolecule cursor = m;
        while (cursor.getParentMolId() != null && guard.add(cursor.getId())) {
            CandidateMolecule parent = byId.get(cursor.getParentMolId());
            if (parent == null) {
                break;
            }
            depth++;
            cursor = parent;
        }
        return depth;
    }

    // ================================================================== 内部

    private EditDtos.ActionOption resolveOption(CandidateMolecule candidate, String actionCode) {
        List<EditActionMapping> mappings = actionMappingMapper.selectList(
                Wrappers.lambdaQuery(EditActionMapping.class)
                        .eq(EditActionMapping::getEnabled, 1)
                        .eq(EditActionMapping::getDeleted, 0));
        String wanted = actionCode.trim().toUpperCase();

        BigDecimal rate = null;
        int sampleSize = 0;
        Map<String, Object> sideEffects = Map.of();
        String dimension = null;
        for (EditActionMapping m : mappings) {
            boolean primary = wanted.equalsIgnoreCase(m.getPrimaryAction());
            boolean fallback = m.getFallbackAction() != null && wanted.equalsIgnoreCase(m.getFallbackAction());
            if (!primary && !fallback) {
                continue;
            }
            if (!ActionMatcher.modalityApplies(m.getAppliesToModality(), candidate.getModalityCode())) {
                continue;
            }
            rate = m.getSuccessRate();
            sampleSize = m.getSampleSize() == null ? 0 : m.getSampleSize();
            sideEffects = jsonCodec.readMap(m.getSideEffectsJson()) == null
                    ? Map.of() : jsonCodec.readMap(m.getSideEffectsJson());
            dimension = m.getGapDimension();
            break;
        }
        if (dimension == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "映射表里没有该动作，或它不适用于当前分子形式：" + actionCode,
                    Map.of("actionCode", actionCode, "modality", candidate.getModalityCode()));
        }
        return new EditDtos.ActionOption(dimension, actionCode, false, rate, sampleSize,
                sideEffects == null ? Map.of() : sideEffects);
    }

    private boolean passedInDimension(Map<String, EvaluationDtos.MetricScore> scores, String dimension) {
        for (Map.Entry<String, EvaluationDtos.MetricScore> e : scores.entrySet()) {
            if (!dimension.equals(GapDimensionMapper.of(e.getKey()))) {
                continue;
            }
            if (Boolean.TRUE.equals(e.getValue().passed())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, EvaluationDtos.MetricScore> currentScores(Long candidateId, Integer versionNo) {
        EvaluationFreeze freeze = freezeMapper.selectOne(Wrappers.lambdaQuery(EvaluationFreeze.class)
                .eq(EvaluationFreeze::getCandidateId, candidateId)
                .eq(EvaluationFreeze::getVersionNo, versionNo)
                .orderByDesc(EvaluationFreeze::getId)
                .last("LIMIT 1"));
        return freeze == null ? Map.of() : parseScores(freeze.getScoresJson());
    }

    /** scores_json → MetricScore。结构不合法时返回空 map，不猜 */
    Map<String, EvaluationDtos.MetricScore> parseScores(String json) {
        Map<String, Object> root = jsonCodec.readMap(json);
        Map<String, EvaluationDtos.MetricScore> out = new LinkedHashMap<>();
        if (root == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            out.put(e.getKey(), new EvaluationDtos.MetricScore(
                    e.getKey(),
                    decimalOf(m.get("value")),
                    decimalOf(m.get("normalized")),
                    boolOf(m.get("passed")),
                    m.get("risk") == null ? null : String.valueOf(m.get("risk")),
                    m.get("note") == null ? null : String.valueOf(m.get("note"))));
        }
        return out;
    }

    private List<Map<String, Object>> mapList(List<EditDtos.Gap> gaps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EditDtos.Gap g : gaps) {
            out.add(g.toMap());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readMapList(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        List<?> parsed = jsonCodec.read(json, List.class);
        if (parsed == null) {
            return out;
        }
        for (Object item : parsed) {
            if (item instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        List<?> parsed = jsonCodec.read(json, List.class);
        if (parsed == null) {
            return out;
        }
        for (Object item : parsed) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static BigDecimal decimalOf(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return v == null ? null : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean boolOf(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private CandidateMolecule loadOwnedCandidate(Long candidateId, Long userId) {
        CandidateMolecule candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "候选不存在：" + candidateId, Map.of("candidateId", candidateId));
        }
        DesignProject project = projectMapper.selectById(candidate.getProjectId());
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.RESOURCE_NOT_FOUND, Map.of("candidateId", candidateId));
        return candidate;
    }
}
