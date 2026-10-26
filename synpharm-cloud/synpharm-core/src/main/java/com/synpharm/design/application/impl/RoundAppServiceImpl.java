package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.AcceptedNextRoundResponse;
import com.synpharm.design.api.dto.NextRoundRequest;
import com.synpharm.design.api.dto.RoundOpenRequest;
import com.synpharm.design.api.dto.RoundResponse;
import com.synpharm.design.application.RoundAppService;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.DesignRound;
import com.synpharm.design.domain.model.ProjectStatus;
import com.synpharm.design.domain.model.RoundStatus;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.DesignRoundMapper;
import com.synpharm.design.support.ProjectReadinessChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮次生命周期实现。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundAppServiceImpl implements RoundAppService {

    private final DesignProjectMapper projectMapper;
    private final DesignRoundMapper roundMapper;
    private final ProjectReadinessChecker readiness;
    private final JsonCodec jsonCodec;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoundResponse open(Long projectId, RoundOpenRequest request) {
        DesignProject project = loadOwned(projectId);
        // 先查「是否已有未关闭轮次」再判项目状态：前者是更精确的诊断，
        // 用户看到「已有未关闭的轮次」立刻知道该去关轮次，而不是去猜状态机
        assertNoOpenRound(projectId);
        assertReadyToOpen(project);

        DesignRound round = insertRound(projectId, nextRoundNo(projectId), request.getStrategyNote(), null);

        // READY → ACTIVE 由「开启第一轮」驱动（设计域状态机 6.1）
        project.setStatus(ProjectStatus.ACTIVE.name());
        project.setCurrentRoundId(round.getId());
        if (project.getStartedAt() == null) {
            project.setStartedAt(LocalDateTime.now());
        }
        projectMapper.updateById(project);
        log.info("项目 {} 开启第 {} 轮（roundId={}）", projectId, round.getRoundNo(), round.getId());
        return toResponse(round);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoundResponse close(Long roundId, String conclusionText) {
        DesignRound round = roundMapper.selectById(roundId);
        if (round == null) {
            throw new BizException(ErrorCode.ROUND_NOT_FOUND, null, Map.of("id", roundId));
        }
        // 轮次没有 user_id，归属只能通过所属项目判定 —— 别人的轮次同样得到 404
        loadOwned(round.getProjectId());

        if (RoundStatus.CLOSED.name().equals(round.getStatus())) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT, "轮次已关闭",
                    Map.of("id", roundId, "status", round.getStatus()));
        }
        round.setStatus(RoundStatus.CLOSED.name());
        round.setConclusionText(conclusionText);
        round.setClosedAt(LocalDateTime.now());
        roundMapper.updateById(round);
        log.info("轮次 {} 已关闭", roundId);
        return toResponse(round);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AcceptedNextRoundResponse nextRound(Long projectId, NextRoundRequest request) {
        DesignProject project = loadOwned(projectId);
        if (!ProjectStatus.ACTIVE.name().equals(project.getStatus())) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_STATUS_INVALID,
                    "只有进行中的项目可以启动下一轮，当前状态: " + project.getStatus(),
                    Map.of("id", projectId, "status", project.getStatus()));
        }
        if (Boolean.TRUE.equals(request.getAutoStartGeneration())) {
            // 绝不静默忽略：生成管线（MQ 消费者 + 计算层）尚未落地，
            // 若默默只开轮次，用户会以为任务已经投出去了
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "生成任务管线尚未落地（阶段 2B 后续），请先只开轮次（autoStartGeneration=false）",
                    Map.of("projectId", projectId));
        }

        assertNoOpenRound(projectId);
        List<DesignRound> rounds = listRounds(projectId);
        if (rounds.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "该项目还没有任何轮次，请先用 POST /api/design/projects/{id}/rounds 开启第一轮",
                    Map.of("projectId", projectId));
        }

        int nextNo = rounds.get(rounds.size() - 1).getRoundNo() + 1;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (request.getSeedFrom() != null) {
            snapshot.put("seedFrom", request.getSeedFrom());
        }
        if (request.getCalibrationId() != null) {
            snapshot.put("calibrationId", request.getCalibrationId());
        }
        DesignRound round = insertRound(projectId, nextNo, request.getStrategyNote(),
                snapshot.isEmpty() ? null : snapshot);

        project.setCurrentRoundId(round.getId());
        projectMapper.updateById(project);

        AcceptedNextRoundResponse response = new AcceptedNextRoundResponse();
        response.setRoundId(round.getId());
        response.setRoundNo(round.getRoundNo());
        // 只开轮次、未投递任务，故 jobId/status 留空
        log.info("项目 {} 启动第 {} 轮（roundId={}，未投递任务）", projectId, round.getRoundNo(), round.getId());
        return response;
    }

    // ------------------------------------------------------------------ 内部

    private DesignProject loadOwned(Long projectId) {
        DesignProject project = projectMapper.selectById(projectId);
        OwnershipGuard.assertOwned(CurrentUser.requireUserId(),
                project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("id", projectId));
        return project;
    }

    /** 只有推导为 READY 的项目才能开轮次；DRAFT 要把缺口原样告诉用户 */
    private void assertReadyToOpen(DesignProject project) {
        if (ProjectStatus.READY.name().equals(project.getStatus())) {
            return;
        }
        if (ProjectStatus.DRAFT.name().equals(project.getStatus())) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_NOT_READY, null,
                    Map.of("id", project.getId(), "readyBlockers", readiness.blockers(project)));
        }
        throw new BizException(ErrorCode.DESIGN_PROJECT_STATUS_INVALID,
                "项目当前状态不允许开启轮次: " + project.getStatus(),
                Map.of("id", project.getId(), "status", project.getStatus()));
    }

    private void assertNoOpenRound(Long projectId) {
        DesignRound open = findOpenRound(projectId);
        if (open != null) {
            throw new BizException(ErrorCode.DESIGN_ROUND_ALREADY_OPEN, null,
                    Map.of("projectId", projectId, "openRoundId", open.getId(), "roundNo", open.getRoundNo()));
        }
    }

    private DesignRound findOpenRound(Long projectId) {
        return roundMapper.selectOne(Wrappers.lambdaQuery(DesignRound.class)
                .eq(DesignRound::getProjectId, projectId)
                .ne(DesignRound::getStatus, RoundStatus.CLOSED.name())
                .orderByDesc(DesignRound::getRoundNo)
                .last("LIMIT 1"));
    }

    private List<DesignRound> listRounds(Long projectId) {
        return roundMapper.selectList(Wrappers.lambdaQuery(DesignRound.class)
                .eq(DesignRound::getProjectId, projectId)
                .orderByAsc(DesignRound::getRoundNo));
    }

    private int nextRoundNo(Long projectId) {
        List<DesignRound> rounds = listRounds(projectId);
        return rounds.isEmpty() ? 1 : rounds.get(rounds.size() - 1).getRoundNo() + 1;
    }

    private DesignRound insertRound(Long projectId, int roundNo, String strategyNote, Map<String, Object> metrics) {
        DesignRound round = new DesignRound();
        round.setProjectId(projectId);
        round.setRoundNo(roundNo);
        round.setStrategyNote(strategyNote);
        round.setStatus(RoundStatus.PLANNED.name());
        round.setOpenedAt(LocalDateTime.now());
        if (metrics != null && !metrics.isEmpty()) {
            round.setMetricsJson(jsonCodec.write(metrics));
        }
        roundMapper.insert(round);
        return round;
    }

    private RoundResponse toResponse(DesignRound round) {
        RoundResponse response = new RoundResponse();
        response.setId(round.getId());
        response.setProjectId(round.getProjectId());
        response.setRoundNo(round.getRoundNo());
        response.setStrategyNote(round.getStrategyNote());
        response.setStatus(round.getStatus());
        response.setConclusionText(round.getConclusionText());
        response.setMetrics(jsonCodec.readMap(round.getMetricsJson()));
        response.setOpenedAt(round.getOpenedAt());
        response.setClosedAt(round.getClosedAt());
        return response;
    }
}
