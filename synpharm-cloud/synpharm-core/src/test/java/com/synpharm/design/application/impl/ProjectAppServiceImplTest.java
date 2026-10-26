package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.ObjectiveDto;
import com.synpharm.design.api.dto.ProjectCreatedResponse;
import com.synpharm.design.api.dto.ProjectCreateRequest;
import com.synpharm.design.api.dto.ProjectPatchRequest;
import com.synpharm.design.api.dto.ProjectTargetBindRequest;
import com.synpharm.design.application.ConstraintAppService;
import com.synpharm.design.domain.model.ConstraintSet;
import com.synpharm.design.domain.model.ConstraintStatus;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.ProjectStatus;
import com.synpharm.design.domain.model.ProjectTarget;
import com.synpharm.design.domain.model.Target;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.ConstraintSetMapper;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.DesignRoundMapper;
import com.synpharm.design.infrastructure.persistence.ProjectTargetMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目生命周期测试。
 *
 * <p>核心是 {@code READY} 这个**推导态**：它必须由「已绑靶点 且 已激活约束集」算出来，
 * 缺口要如实写进 {@code readyBlockers}（红线 R2：不得静默就绪）。
 * 另外锁死一条容易写错、又不显眼的规则：靶点集合一旦进入 ACTIVE 就冻结。
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectAppServiceImplTest {

    private static final long ME = 100L;
    private static final long OTHER = 200L;
    private static final long MODALITY_SMALL = 501L;
    private static final long TARGET_OK = 900L;
    private static final long TARGET_NO_SEQ = 901L;

    @Mock private DesignProjectMapper projectMapper;
    @Mock private ProjectTargetMapper projectTargetMapper;
    @Mock private TargetMapper targetMapper;
    @Mock private DesignRoundMapper roundMapper;
    @Mock private CandidateMoleculeMapper candidateMapper;
    @Mock private ConstraintSetMapper constraintSetMapper;
    @Mock private DesignLibraryItemMapper libraryItemMapper;
    @Mock private ConstraintAppService constraintAppService;
    @Mock private ProjectReadinessChecker readiness;

    private ProjectAppServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProjectAppServiceImpl(projectMapper, projectTargetMapper, targetMapper, roundMapper,
                candidateMapper, constraintSetMapper, libraryItemMapper, constraintAppService, readiness,
                new JsonCodec(new ObjectMapper()));
        CurrentUserTestSupport.login(ME);
        // 就绪度判定已抽成独立组件：默认「缺约束集」，需要别的缺口时在用例内覆盖
        when(readiness.blockers(any(DesignProject.class))).thenReturn(List.of("尚未激活约束集"));
        // 默认：没有绑定、没有约束集、没有轮次、没有候选项
        when(projectTargetMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(roundMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(candidateMapper.selectMaps(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(targetMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        // MyBatis-Plus 会在 insert 后把自增主键回填到实体上，mock 必须模拟这一点，
        // 否则后续按 id 组装响应会拿到 null
        when(projectMapper.insert(any(DesignProject.class))).thenAnswer(invocation -> {
            DesignProject inserted = invocation.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(1L);
            }
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    private static Target target(Long id, String name, String sequence) {
        Target t = new Target();
        t.setId(id);
        t.setName(name);
        t.setUniprotId("P" + id);
        t.setSequence(sequence);
        return t;
    }

    private static DesignProject project(Long id, Long ownerId, ProjectStatus status) {
        DesignProject p = new DesignProject();
        p.setId(id);
        p.setProjectNo("P260101000000ABCD");
        p.setUserId(ownerId);
        p.setName("测试项目");
        p.setStatus(status.name());
        return p;
    }

    private static ProjectTarget primaryBinding(Long projectId, Long targetId) {
        ProjectTarget b = new ProjectTarget();
        b.setId(1L);
        b.setProjectId(projectId);
        b.setTargetId(targetId);
        b.setIsPrimary(1);
        b.setRole("WILD_TYPE");
        b.setSortNo(0);
        return b;
    }

    private void stubModality() {
        DesignLibraryItem item = new DesignLibraryItem();
        item.setId(MODALITY_SMALL);
        item.setCategory("MODALITY");
        when(libraryItemMapper.selectById(MODALITY_SMALL)).thenReturn(item);
    }

    private static ProjectCreateRequest createRequest(boolean... primaries) {
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setName("EGFR 抑制剂设计");
        request.setModalityId(MODALITY_SMALL);
        List<ProjectTargetBindRequest> targets = new java.util.ArrayList<>();
        boolean first = true;
        for (boolean primary : primaries) {
            ProjectTargetBindRequest b = new ProjectTargetBindRequest();
            b.setTargetId(first ? TARGET_OK : TARGET_NO_SEQ);
            b.setIsPrimary(primary);
            targets.add(b);
            first = false;
        }
        request.setTargets(targets);
        return request;
    }

    @Test
    @DisplayName("创建：无主靶点 -> 400")
    void createRequiresExactlyOnePrimary() {
        stubModality();
        when(targetMapper.selectById(TARGET_OK)).thenReturn(target(TARGET_OK, "EGFR", "MST..."));

        assertThatThrownBy(() -> service.create(createRequest(false)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("创建：两个主靶点 -> 409 DESIGN_PRIMARY_TARGET_EXISTS")
    void createRejectsTwoPrimaries() {
        stubModality();
        when(targetMapper.selectById(anyLong())).thenReturn(target(TARGET_OK, "EGFR", "MST..."));

        assertThatThrownBy(() -> service.create(createRequest(true, true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PRIMARY_TARGET_EXISTS);
    }

    @Test
    @DisplayName("创建：modalityId 不是知识库 MODALITY 项 -> 400")
    void createRejectsWrongModality() {
        DesignLibraryItem disease = new DesignLibraryItem();
        disease.setId(MODALITY_SMALL);
        disease.setCategory("DISEASE");
        when(libraryItemMapper.selectById(MODALITY_SMALL)).thenReturn(disease);

        assertThatThrownBy(() -> service.create(createRequest(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(projectMapper, never()).insert(any(DesignProject.class));
    }

    @Test
    @DisplayName("创建：优化目标维度不在 8 大维度内 -> 400")
    void createRejectsUnknownObjectiveDimension() {
        stubModality();
        when(targetMapper.selectById(TARGET_OK)).thenReturn(target(TARGET_OK, "EGFR", "MST..."));
        ProjectCreateRequest request = createRequest(true);
        ObjectiveDto objective = new ObjectiveDto();
        objective.setDimension("MAGIC");
        request.setObjectives(List.of(objective));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("创建：未给约束规则 -> DRAFT，且显式列出缺口（不静默就绪）")
    void createWithoutConstraintsReportsBlockers() {
        stubModality();
        when(targetMapper.selectById(TARGET_OK)).thenReturn(target(TARGET_OK, "EGFR", "MST..."));

        ProjectCreatedResponse response = service.create(createRequest(true));

        assertThat(response.getStatus()).isEqualTo(ProjectStatus.DRAFT.name());
        assertThat(response.getReadyBlockers()).contains("尚未激活约束集");
        assertThat(response.getProjectNo()).startsWith("P");
        verify(projectMapper).insert(any(DesignProject.class));
        verify(projectTargetMapper).insert(any(ProjectTarget.class));
    }

    @Test
    @DisplayName("创建：主靶点缺序列 -> 缺口写明「主靶点缺少序列」")
    void createReportsMissingSequence() {
        stubModality();
        when(targetMapper.selectById(TARGET_OK)).thenReturn(target(TARGET_OK, "EGFR", null));
        when(readiness.blockers(any(DesignProject.class))).thenReturn(List.of("主靶点缺少序列：EGFR"));

        ProjectCreatedResponse response = service.create(createRequest(true));

        assertThat(response.getStatus()).isEqualTo(ProjectStatus.DRAFT.name());
        assertThat(response.getReadyBlockers()).anyMatch(b -> b.startsWith("主靶点缺少序列"));
    }

    @Test
    @DisplayName("创建：内联约束规则 -> 建集并激活，项目仍因靶点缺序列不可 READY")
    void createWithInlineRulesActivatesConstraintSet() {
        stubModality();
        when(targetMapper.selectById(TARGET_OK)).thenReturn(target(TARGET_OK, "EGFR", null));
        when(projectTargetMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(primaryBinding(1L, TARGET_OK)));
        ConstraintSetResponse draft = new ConstraintSetResponse();
        draft.setId(77L);
        draft.setStatus(ConstraintStatus.DRAFT.name());
        ConstraintSetResponse active = new ConstraintSetResponse();
        active.setId(77L);
        active.setStatus(ConstraintStatus.ACTIVE.name());
        when(constraintAppService.create(any())).thenReturn(draft);
        when(constraintAppService.activate(77L)).thenReturn(active);

        ProjectCreateRequest request = createRequest(true);
        request.setConstraintRules(List.of(new com.synpharm.design.api.dto.ConstraintRuleDto()));
        service.create(request);

        verify(constraintAppService).create(any());
        verify(constraintAppService).activate(77L);
        ArgumentCaptor<DesignProject> captor = ArgumentCaptor.forClass(DesignProject.class);
        verify(projectMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getValue().getActiveConstraintSetId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("创建：目标维度合并进 goal_metric_json")
    void createStoresObjectivesIntoGoalMetric() {
        stubModality();
        when(targetMapper.selectById(TARGET_OK)).thenReturn(target(TARGET_OK, "EGFR", "MST..."));
        ProjectCreateRequest request = createRequest(true);
        ObjectiveDto objective = new ObjectiveDto();
        objective.setDimension("ADMET");
        objective.setOperator("LTE");
        objective.setWeight(5);
        request.setObjectives(List.of(objective));

        service.create(request);

        ArgumentCaptor<DesignProject> captor = ArgumentCaptor.forClass(DesignProject.class);
        verify(projectMapper).insert(captor.capture());
        assertThat(captor.getValue().getGoalMetricJson()).contains("ADMET").contains("LTE");
    }

    @Test
    @DisplayName("绑靶点：项目已 ACTIVE -> 409（靶点集合冻结）")
    void bindTargetRejectsFrozenProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.ACTIVE));

        ProjectTargetBindRequest request = new ProjectTargetBindRequest();
        request.setTargetId(TARGET_OK);

        assertThatThrownBy(() -> service.bindTarget(1L, request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_STATUS_INVALID);
        verify(projectTargetMapper, never()).insert(any(ProjectTarget.class));
    }

    @Test
    @DisplayName("绑靶点：重复绑定 -> 409 DESIGN_TARGET_DUPLICATED")
    void bindTargetRejectsDuplication() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.DRAFT));
        when(targetMapper.selectById(TARGET_OK)).thenReturn(target(TARGET_OK, "EGFR", "MST..."));
        when(projectTargetMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ProjectTargetBindRequest request = new ProjectTargetBindRequest();
        request.setTargetId(TARGET_OK);

        assertThatThrownBy(() -> service.bindTarget(1L, request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_TARGET_DUPLICATED);
    }

    @Test
    @DisplayName("绑靶点：已有主靶点再绑主靶点 -> 409 DESIGN_PRIMARY_TARGET_EXISTS")
    void bindTargetRejectsSecondPrimary() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.DRAFT));
        when(targetMapper.selectById(TARGET_NO_SEQ)).thenReturn(target(TARGET_NO_SEQ, "KRAS", "MTE..."));
        when(projectTargetMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(projectTargetMapper.selectOne(any(Wrapper.class))).thenReturn(primaryBinding(1L, TARGET_OK));

        ProjectTargetBindRequest request = new ProjectTargetBindRequest();
        request.setTargetId(TARGET_NO_SEQ);
        request.setIsPrimary(true);

        assertThatThrownBy(() -> service.bindTarget(1L, request))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PRIMARY_TARGET_EXISTS);
    }

    @Test
    @DisplayName("解绑：未绑定该靶点 -> 404 TARGET_NOT_FOUND")
    void unbindTargetRejectsMissingBinding() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.DRAFT));
        when(projectTargetMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.unbindTarget(1L, TARGET_OK))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TARGET_NOT_FOUND);
    }

    @Test
    @DisplayName("解绑主靶点：回退 DRAFT 并提示未指定主靶点")
    void unbindPrimaryFallsBackToDraft() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.READY));
        when(projectTargetMapper.selectOne(any(Wrapper.class))).thenReturn(primaryBinding(1L, TARGET_OK));
        when(projectTargetMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(primaryBinding(1L, TARGET_OK)));

        service.unbindTarget(1L, TARGET_OK);

        verify(projectTargetMapper).deleteById(1L);
        ArgumentCaptor<DesignProject> captor = ArgumentCaptor.forClass(DesignProject.class);
        verify(projectMapper).updateById(captor.capture());
        // 主靶点被解绑后不自动提升其它靶点，而是如实回到 DRAFT
        assertThat(captor.getValue().getStatus()).isEqualTo(ProjectStatus.DRAFT.name());
    }

    @Test
    @DisplayName("访问他人项目 -> 404（不返回 403）")
    void getRejectsForeignProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, OTHER, ProjectStatus.DRAFT));

        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("绑定约束集：activate=false 且约束集仍是 DRAFT -> 409")
    void bindConstraintSetRequiresActiveWhenNotAutoActivating() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.DRAFT));
        ConstraintSet set = new ConstraintSet();
        set.setId(77L);
        set.setUserId(ME);
        set.setStatus(ConstraintStatus.DRAFT.name());
        when(constraintSetMapper.selectById(77L)).thenReturn(set);

        assertThatThrownBy(() -> service.bindConstraintSet(1L, 77L, false))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_CONFLICT);
    }

    @Test
    @DisplayName("绑定约束集：activate=true 时自动激活并置为生效")
    void bindConstraintSetActivatesDraft() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.DRAFT));
        ConstraintSet set = new ConstraintSet();
        set.setId(77L);
        set.setUserId(ME);
        set.setStatus(ConstraintStatus.DRAFT.name());
        when(constraintSetMapper.selectById(77L)).thenReturn(set);
        ConstraintSetResponse active = new ConstraintSetResponse();
        active.setId(77L);
        active.setStatus(ConstraintStatus.ACTIVE.name());
        when(constraintAppService.activate(77L)).thenReturn(active);

        assertThat(service.bindConstraintSet(1L, 77L, true).getId()).isEqualTo(77L);

        ArgumentCaptor<DesignProject> captor = ArgumentCaptor.forClass(DesignProject.class);
        verify(projectMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getValue().getActiveConstraintSetId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("编辑：项目已 ACTIVE -> 409")
    void updateRejectsFrozenProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, ME, ProjectStatus.ACTIVE));

        assertThatThrownBy(() -> service.update(1L, new ProjectPatchRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_STATUS_INVALID);
    }

    @Test
    @DisplayName("列表：非法 status -> 400")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list("NOPE", null, 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
