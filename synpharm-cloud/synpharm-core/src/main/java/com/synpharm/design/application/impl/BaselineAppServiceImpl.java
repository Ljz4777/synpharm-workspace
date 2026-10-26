package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.BaselinePreviewResponse;
import com.synpharm.design.api.dto.BaselineResponse;
import com.synpharm.design.application.BaselineAppService;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.infrastructure.persistence.BaselineSnapshotMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.support.BaselineAssembler;
import com.synpharm.design.support.SerialNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 需求基线用例实现。
 *
 * <p>两个不变量：
 * <ol>
 *   <li><b>八块不齐不得冻结</b> —— 缺项时抛 {@code BASELINE_INCOMPLETE} 并带上清单，
 *       不允许「先冻结再补」，否则生成任务会拿到半成品快照。</li>
 *   <li><b>同内容不升版本</b> —— 冻结幂等，靠 {@code content_hash} 判定。
 *       用户重复点「冻结」不应产生一堆内容相同的快照。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaselineAppServiceImpl implements BaselineAppService {

    private final DesignProjectMapper designProjectMapper;
    private final BaselineSnapshotMapper baselineSnapshotMapper;
    private final BaselineAssembler baselineAssembler;
    private final JsonCodec jsonCodec;

    @Override
    public BaselinePreviewResponse preview(Long projectId) {
        DesignProject project = loadOwnedProject(projectId);
        return baselineAssembler.assemble(project).getPreview();
    }

    @Override
    @Transactional
    public BaselineResponse freeze(Long projectId, String expectedContentHash) {
        Long userId = CurrentUser.requireUserId();
        DesignProject project = loadOwnedProject(projectId);

        BaselineAssembler.Assembled assembled = baselineAssembler.assemble(project);

        if (StringUtils.hasText(expectedContentHash)
                && !expectedContentHash.equals(assembled.getContentHash())) {
            throw new BizException(ErrorCode.BASELINE_STALE,
                    "预检之后项目已被修改（当前哈希 " + shortHash(assembled.getContentHash())
                            + "，预检时 " + shortHash(expectedContentHash) + "），请重新预检");
        }

        if (!assembled.isReady()) {
            throw new BizException(ErrorCode.BASELINE_INCOMPLETE,
                    "基线八块不完整，无法冻结：" + firstErrors(assembled, 3));
        }
        if (!StringUtils.hasText(assembled.getModalityCode())) {
            // 理论到不了这里（MODALITY 是必需块），留作防御
            throw new BizException(ErrorCode.BASELINE_INCOMPLETE, "未确定分子形式，无法冻结");
        }

        BaselineSnapshot active = findActive(projectId);
        if (active != null && assembled.getContentHash().equals(active.getContentHash())) {
            // 幂等命中：同内容重复冻结
            log.info("基线冻结幂等命中 projectId={} snapshotNo={}", projectId, active.getSnapshotNo());
            return toResponse(active);
        }

        BaselineSnapshot snapshot = new BaselineSnapshot();
        snapshot.setSnapshotNo(SerialNumbers.baselineSnapshotNo());
        snapshot.setProjectId(projectId);
        snapshot.setProjectVersion(active == null ? 1 : active.getProjectVersion() + 1);
        snapshot.setUserId(userId);
        snapshot.setTargetsJson(assembled.getTargetsJson());
        snapshot.setStructureJson(assembled.getStructureJson());
        snapshot.setModalityCode(assembled.getModalityCode());
        snapshot.setHardRulesJson(assembled.getHardRulesJson());
        snapshot.setSoftRulesJson(assembled.getSoftRulesJson());
        snapshot.setMetricsJson(assembled.getMetricsJson());
        snapshot.setQuotaJson(assembled.getQuotaJson());
        snapshot.setRisksJson(assembled.getRisksJson());
        snapshot.setCompletenessJson(assembled.getCompletenessJson());
        snapshot.setContentHash(assembled.getContentHash());
        snapshot.setStatus("ACTIVE");
        baselineSnapshotMapper.insert(snapshot);

        // 旧快照降级必须在插入之后：superseded_by 是指向新快照的外键
        if (active != null) {
            BaselineSnapshot patch = new BaselineSnapshot();
            patch.setId(active.getId());
            patch.setStatus("SUPERSEDED");
            patch.setSupersededBy(snapshot.getId());
            baselineSnapshotMapper.updateById(patch);
        }

        DesignProject projectPatch = new DesignProject();
        projectPatch.setId(projectId);
        projectPatch.setCurrentBaselineId(snapshot.getId());
        projectPatch.setProjectVersion(snapshot.getProjectVersion());
        designProjectMapper.updateById(projectPatch);

        log.info("基线已冻结 projectId={} snapshotNo={} version={}",
                projectId, snapshot.getSnapshotNo(), snapshot.getProjectVersion());
        return toResponse(baselineSnapshotMapper.selectById(snapshot.getId()));
    }

    @Override
    public BaselineResponse current(Long projectId) {
        loadOwnedProject(projectId);
        BaselineSnapshot active = findActive(projectId);
        if (active == null) {
            throw new BizException(ErrorCode.BASELINE_NOT_FOUND, "该项目还没有冻结过基线");
        }
        return toResponse(active);
    }

    @Override
    public List<BaselineResponse> history(Long projectId) {
        loadOwnedProject(projectId);
        return baselineSnapshotMapper.selectList(
                        Wrappers.lambdaQuery(BaselineSnapshot.class)
                                .eq(BaselineSnapshot::getProjectId, projectId)
                                .orderByDesc(BaselineSnapshot::getProjectVersion))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ 内部

    private DesignProject loadOwnedProject(Long projectId) {
        // 先取身份再查库：匿名请求不该产生任何一次数据库往返
        Long userId = CurrentUser.requireUserId();
        DesignProject project = designProjectMapper.selectById(projectId);
        OwnershipGuard.assertOwned(userId,
                project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", projectId));
        return project;
    }

    private BaselineSnapshot findActive(Long projectId) {
        return baselineSnapshotMapper.selectOne(
                Wrappers.lambdaQuery(BaselineSnapshot.class)
                        .eq(BaselineSnapshot::getProjectId, projectId)
                        .eq(BaselineSnapshot::getStatus, "ACTIVE")
                        .orderByDesc(BaselineSnapshot::getProjectVersion)
                        .last("LIMIT 1"));
    }

    private String firstErrors(BaselineAssembler.Assembled assembled, int limit) {
        return assembled.getPreview().getGaps().stream()
                .filter(g -> "ERROR".equals(g.getSeverity()))
                .limit(limit)
                .map(BaselinePreviewResponse.Gap::getMessage)
                .collect(Collectors.joining("；"));
    }

    private String shortHash(String hash) {
        return hash == null || hash.length() <= 8 ? String.valueOf(hash) : hash.substring(0, 8);
    }

    private BaselineResponse toResponse(BaselineSnapshot s) {
        BaselineResponse r = new BaselineResponse();
        r.setId(s.getId());
        r.setSnapshotNo(s.getSnapshotNo());
        r.setProjectId(s.getProjectId());
        r.setProjectVersion(s.getProjectVersion());
        r.setUserId(s.getUserId());
        r.setTargets(jsonCodec.readMap(s.getTargetsJson()));
        r.setStructure(jsonCodec.readMap(s.getStructureJson()));
        r.setModalityCode(s.getModalityCode());
        r.setHardRules(jsonCodec.readMap(s.getHardRulesJson()));
        r.setSoftRules(jsonCodec.readMap(s.getSoftRulesJson()));
        r.setMetrics(jsonCodec.readMap(s.getMetricsJson()));
        r.setQuota(jsonCodec.readMap(s.getQuotaJson()));
        r.setRisks(jsonCodec.readMap(s.getRisksJson()));
        Map<String, Object> rawCompleteness = jsonCodec.readMap(s.getCompletenessJson());
        if (rawCompleteness != null) {
            Map<String, String> completeness = new LinkedHashMap<>();
            rawCompleteness.forEach((k, v) -> completeness.put(k, String.valueOf(v)));
            r.setCompleteness(completeness);
        }
        r.setContentHash(s.getContentHash());
        r.setStatus(s.getStatus());
        r.setSupersededBy(s.getSupersededBy());
        r.setCreatedAt(s.getCreatedAt());
        return r;
    }
}
