package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.AcceptedNextRoundResponse;
import com.synpharm.design.api.dto.NextRoundRequest;
import com.synpharm.design.api.dto.RoundOpenRequest;
import com.synpharm.design.api.dto.RoundResponse;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.DesignRound;
import com.synpharm.design.domain.model.ProjectStatus;
import com.synpharm.design.domain.model.RoundStatus;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.DesignRoundMapper;
import com.synpharm.design.support.ProjectReadinessChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮次生命周期测试。
 *
 * <p>三个关键点：①开轮次的前置是**推导出的 READY**，DRAFT 时必须把缺口原样回给用户；
 * ②同一项目同时只允许一个未关闭轮次，这既防呆也顺带成了 next-round 的重放保护；
 * ③{@code autoStartGeneration=true} 时生成管线还没落地，必须**显式拒绝**而不是静默只开轮次。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoundAppServiceImplTest {

    private static final long ME = 100L;
    private static final long OTHER = 200L;

    @Mock private DesignProjectMapper projectMapper;
    @Mock private DesignRoundMapper roundMapper;
    @Mock private ProjectReadinessChecker readiness;

    private RoundAppServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoundAppServiceImpl(projectMapper, roundMapper, readiness, new JsonCodec(new ObjectMapper()));
        CurrentUserTestSupport.login(ME);
        when(roundMapper.insert(any(DesignRound.class))).thenAnswer(invocation -> {
            DesignRound round = invocation.getArgument(0);
            if (round.getId() == null) {
                round.setId(700L);
            }
            return 1;
        });
        when(roundMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(readiness.blockers(any(DesignProject.class))).thenReturn(List.of("尚未激活约束集"));
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    private static DesignProject project(ProjectStatus status, Long ownerId) {
        DesignProject p = new DesignProject();
        p.setId(1L);
        p.setProjectNo("P260101000000ABCD");
        p.setUserId(ownerId);
        p.setName("测试项目");
        p.setStatus(status.name());
        return p;
    }

    private static DesignRound round(Long id, Long projectId, int roundNo, RoundStatus status) {
        DesignRound r = new DesignRound();
        r.setId(id);
        r.setProjectId(projectId);
        r.setRoundNo(roundNo);
        r.setStatus(status.name());
        return r;
    }

    @Test
    @DisplayName("开轮次：项目 DRAFT -> 40940，details 带 readyBlockers")
    void openRejectsDraftProjectWithBlockers() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.DRAFT, ME));
        when(readiness.blockers(any(DesignProject.class))).thenReturn(List.of("尚未绑定靶点", "尚未激活约束集"));

        assertThatThrownBy(() -> service.open(1L, new RoundOpenRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("靶点或约束集尚未就绪")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_READY);
        verify(roundMapper, never()).insert(any(DesignRound.class));
    }

    @Test
    @DisplayName("开轮次：READY -> 建 PLANNED 轮次，项目转 ACTIVE 并写 currentRoundId/startedAt")
    void openMovesReadyProjectToActive() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.READY, ME));

        RoundResponse response = service.open(1L, new RoundOpenRequest());

        assertThat(response.getRoundNo()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(RoundStatus.PLANNED.name());
        ArgumentCaptor<DesignProject> captor = ArgumentCaptor.forClass(DesignProject.class);
        verify(projectMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProjectStatus.ACTIVE.name());
        assertThat(captor.getValue().getCurrentRoundId()).isEqualTo(700L);
        assertThat(captor.getValue().getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("开轮次：已有未关闭轮次 -> 40942")
    void openRejectsSecondOpenRound() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.READY, ME));
        when(roundMapper.selectOne(any(Wrapper.class))).thenReturn(round(9L, 1L, 1, RoundStatus.PLANNED));

        assertThatThrownBy(() -> service.open(1L, new RoundOpenRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_ROUND_ALREADY_OPEN);
    }

    @Test
    @DisplayName("开轮次：项目已 ACTIVE（非 READY）-> 40941")
    void openRejectsNonReadyNonDraftProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.ACTIVE, ME));

        assertThatThrownBy(() -> service.open(1L, new RoundOpenRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_STATUS_INVALID);
    }

    @Test
    @DisplayName("开轮次：他人项目 -> 40440（不返回 403）")
    void openRejectsForeignProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.READY, OTHER));

        assertThatThrownBy(() -> service.open(1L, new RoundOpenRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("关轮次：轮次不存在 -> 40453")
    void closeRejectsMissingRound() {
        when(roundMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.close(9L, "结论"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ROUND_NOT_FOUND);
    }

    @Test
    @DisplayName("关轮次：轮次属于他人项目 -> 40440（轮次无属主列，靠项目判定）")
    void closeRejectsRoundOfForeignProject() {
        when(roundMapper.selectById(9L)).thenReturn(round(9L, 1L, 1, RoundStatus.PLANNED));
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.ACTIVE, OTHER));

        assertThatThrownBy(() -> service.close(9L, "结论"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("关轮次：已关闭 -> 40901")
    void closeRejectsAlreadyClosedRound() {
        when(roundMapper.selectById(9L)).thenReturn(round(9L, 1L, 1, RoundStatus.CLOSED));
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.ACTIVE, ME));

        assertThatThrownBy(() -> service.close(9L, "结论"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_CONFLICT);
    }

    @Test
    @DisplayName("关轮次：写结论 + CLOSED + closedAt")
    void closeWritesConclusion() {
        when(roundMapper.selectById(9L)).thenReturn(round(9L, 1L, 1, RoundStatus.REVIEWING));
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.ACTIVE, ME));

        RoundResponse response = service.close(9L, "本轮命中率 0.35");

        assertThat(response.getStatus()).isEqualTo(RoundStatus.CLOSED.name());
        assertThat(response.getConclusionText()).isEqualTo("本轮命中率 0.35");
        ArgumentCaptor<DesignRound> captor = ArgumentCaptor.forClass(DesignRound.class);
        verify(roundMapper).updateById(captor.capture());
        assertThat(captor.getValue().getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("下一轮：项目非 ACTIVE -> 40941")
    void nextRoundRejectsNonActiveProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.READY, ME));

        assertThatThrownBy(() -> service.nextRound(1L, new NextRoundRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_STATUS_INVALID);
    }

    @Test
    @DisplayName("下一轮：autoStartGeneration=true -> 显式 503，且不建轮次（不静默忽略）")
    void nextRoundRejectsAutoStartGenerationUntilPipelineExists() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.ACTIVE, ME));
        when(roundMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(roundMapper.selectList(any(Wrapper.class))).thenReturn(List.of(round(9L, 1L, 1, RoundStatus.CLOSED)));

        NextRoundRequest request = new NextRoundRequest();
        request.setAutoStartGeneration(true);

        assertThatThrownBy(() -> service.nextRound(1L, request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
        verify(roundMapper, never()).insert(any(DesignRound.class));
    }

    @Test
    @DisplayName("下一轮：还没有任何轮次 -> 40901（先开第一轮）")
    void nextRoundRequiresExistingRound() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.ACTIVE, ME));
        when(roundMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(roundMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.nextRound(1L, new NextRoundRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_CONFLICT);
    }

    @Test
    @DisplayName("下一轮：上一轮已关 -> 开第 2 轮，jobId/status 留空")
    void nextRoundOpensNextRound() {
        when(projectMapper.selectById(1L)).thenReturn(project(ProjectStatus.ACTIVE, ME));
        when(roundMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(roundMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(round(9L, 1L, 1, RoundStatus.CLOSED)));

        NextRoundRequest request = new NextRoundRequest();
        request.setSeedFrom("EXPERIMENT_BEST");
        AcceptedNextRoundResponse response = service.nextRound(1L, request);

        assertThat(response.getRoundNo()).isEqualTo(2);
        assertThat(response.getJobId()).isNull();
        assertThat(response.getStatus()).isNull();

        ArgumentCaptor<DesignRound> roundCaptor = ArgumentCaptor.forClass(DesignRound.class);
        verify(roundMapper).insert(roundCaptor.capture());
        assertThat(roundCaptor.getValue().getMetricsJson()).contains("EXPERIMENT_BEST");
    }
}
