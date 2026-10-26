package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.BaselinePreviewResponse;
import com.synpharm.design.api.dto.BaselineResponse;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.infrastructure.persistence.BaselineSnapshotMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.support.BaselineAssembler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 基线冻结用例测试。
 *
 * <p>三个不变量，破了任何一个都会让「快照」这个词失去意义：
 * <ol>
 *   <li><b>缺项不冻结</b>：{@code BASELINE_INCOMPLETE} 且一行都不写库 ——
 *       否则生成任务会拿到半成品快照并且无从知道少了什么。</li>
 *   <li><b>同内容不升版本</b>：重复点冻结返回同一个快照，不产生 v2/v3/v4 噪音。</li>
 *   <li><b>内容变更才升版本</b>：新快照 {@code ACTIVE}，旧快照 {@code SUPERSEDED} 且
 *       回填 {@code superseded_by} —— 历史链不能断，否则无法解释「当时凭什么这么决策」。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaselineAppServiceImplTest {

    private static final long ME = 100L;
    private static final long PROJECT = 900L;
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Mock private DesignProjectMapper designProjectMapper;
    @Mock private BaselineSnapshotMapper baselineSnapshotMapper;
    @Mock private BaselineAssembler baselineAssembler;
    @Spy private JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    @InjectMocks private BaselineAppServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                BaselineSnapshot.class);
        CurrentUserTestSupport.login(ME);

        DesignProject project = new DesignProject();
        project.setId(PROJECT);
        project.setUserId(ME);
        when(designProjectMapper.selectById(PROJECT)).thenReturn(project);
        when(baselineSnapshotMapper.selectOne(any())).thenReturn(null);
        when(baselineSnapshotMapper.selectList(any())).thenReturn(Collections.emptyList());
        // insert 时回填自增 id，模拟 MyBatis-Plus 的行为
        doAnswer(inv -> {
            BaselineSnapshot s = inv.getArgument(0);
            s.setId(555L);
            when(baselineSnapshotMapper.selectById(555L)).thenReturn(s);
            return 1;
        }).when(baselineSnapshotMapper).insert(any());
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    // ------------------------------------------------------------------ 构造装配结果

    private static BaselineAssembler.Assembled assembled(String hash, boolean ready, List<String> errorMessages) {
        BaselineAssembler.Assembled a = Mockito.mock(BaselineAssembler.Assembled.class);
        when(a.getContentHash()).thenReturn(hash);
        when(a.isReady()).thenReturn(ready);
        when(a.getModalityCode()).thenReturn("MAB");
        when(a.getTargetsJson()).thenReturn("{\"primaryTargetId\":1}");
        when(a.getStructureJson()).thenReturn("{\"structures\":[]}");
        when(a.getHardRulesJson()).thenReturn("{\"items\":[]}");
        when(a.getSoftRulesJson()).thenReturn("{\"items\":[]}");
        when(a.getMetricsJson()).thenReturn("{\"items\":[]}");
        when(a.getQuotaJson()).thenReturn("{\"minCandidates\":20}");
        when(a.getRisksJson()).thenReturn("{\"items\":[]}");
        when(a.getCompletenessJson()).thenReturn("{\"TARGETS\":\"OK\"}");

        BaselinePreviewResponse preview = new BaselinePreviewResponse();
        preview.setReady(ready);
        preview.setGaps(errorMessages.stream()
                .map(m -> BaselinePreviewResponse.Gap.error("TARGETS", "NO_TARGET", m))
                .collect(java.util.stream.Collectors.toList()));
        when(a.getPreview()).thenReturn(preview);
        return a;
    }

    private static BaselineSnapshot activeSnapshot(long id, int version, String hash) {
        BaselineSnapshot s = new BaselineSnapshot();
        s.setId(id);
        s.setSnapshotNo("BS-1");
        s.setProjectId(PROJECT);
        s.setProjectVersion(version);
        s.setUserId(ME);
        s.setContentHash(hash);
        s.setStatus("ACTIVE");
        return s;
    }

    // ------------------------------------------------------------------ 守卫

    @Test
    @DisplayName("无身份：四个用例全部 401，且不查库（服务自己 fail-closed）")
    void rejectsAnonymous() {
        CurrentUserTestSupport.logout();

        assertThatThrownBy(() -> service.preview(PROJECT))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.freeze(PROJECT, null))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.current(PROJECT))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.history(PROJECT))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(designProjectMapper, never()).selectById(any());
        verify(baselineSnapshotMapper, never()).insert(any());
    }

    @Test
    @DisplayName("项目不属于自己：一律 404（不泄露项目存在性）")
    void foreignProjectIs404() {
        DesignProject other = new DesignProject();
        other.setId(PROJECT);
        other.setUserId(ME + 1);
        when(designProjectMapper.selectById(PROJECT)).thenReturn(other);

        assertThatThrownBy(() -> service.freeze(PROJECT, null))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
        verify(baselineSnapshotMapper, never()).insert(any());
    }

    @Test
    @DisplayName("项目不存在：404，不抛 NPE")
    void missingProjectIs404() {
        when(designProjectMapper.selectById(PROJECT)).thenReturn(null);

        assertThatThrownBy(() -> service.preview(PROJECT))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 冻结

    @Test
    @DisplayName("八块不齐：拒绝冻结，错误文案带上首条缺项，且不写库")
    void incompleteCannotFreeze() {
        when(baselineAssembler.assemble(any()))
                .thenAnswer(inv -> assembled(HASH_A, false, List.of("项目还没有绑定任何靶点，先去「靶点」步骤添加")));

        assertThatThrownBy(() -> service.freeze(PROJECT, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BASELINE_INCOMPLETE);

        assertThatThrownBy(() -> service.freeze(PROJECT, null))
                .hasMessageContaining("还没有绑定任何靶点");

        verify(baselineSnapshotMapper, never()).insert(any());
        verify(designProjectMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("预检哈希不符：拒绝冻结（并发修改），不写库")
    void staleRejected() {
        when(baselineAssembler.assemble(any())).thenAnswer(inv -> assembled(HASH_B, true, List.of()));

        assertThatThrownBy(() -> service.freeze(PROJECT, HASH_A))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BASELINE_STALE);

        verify(baselineSnapshotMapper, never()).insert(any());
    }

    @Test
    @DisplayName("首次冻结：写 v1、状态 ACTIVE，并回写 project.currentBaselineId")
    void firstFreezeCreatesVersionOne() {
        when(baselineAssembler.assemble(any())).thenAnswer(inv -> assembled(HASH_A, true, List.of()));

        BaselineResponse resp = service.freeze(PROJECT, HASH_A);

        ArgumentCaptor<BaselineSnapshot> inserted = ArgumentCaptor.forClass(BaselineSnapshot.class);
        verify(baselineSnapshotMapper).insert(inserted.capture());
        BaselineSnapshot saved = inserted.getValue();
        assertThat(saved.getProjectVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getContentHash()).isEqualTo(HASH_A);
        assertThat(saved.getProjectId()).isEqualTo(PROJECT);
        assertThat(saved.getUserId()).isEqualTo(ME);
        assertThat(saved.getSnapshotNo()).isNotBlank();

        ArgumentCaptor<DesignProject> patched = ArgumentCaptor.forClass(DesignProject.class);
        verify(designProjectMapper).updateById(patched.capture());
        assertThat(patched.getValue().getCurrentBaselineId()).isEqualTo(555L);
        assertThat(patched.getValue().getProjectVersion()).isEqualTo(1);

        assertThat(resp.getId()).isEqualTo(555L);
        assertThat(resp.getProjectVersion()).isEqualTo(1);
        assertThat(resp.getModalityCode()).isEqualTo("MAB");
        assertThat(resp.getCompleteness()).containsEntry("TARGETS", "OK");
    }

    @Test
    @DisplayName("同内容重复冻结：幂等返回同一快照，不新增也不降级旧版")
    void repeatedFreezeIsIdempotent() {
        BaselineSnapshot active = activeSnapshot(700L, 1, HASH_A);
        when(baselineSnapshotMapper.selectOne(any())).thenReturn(active);
        when(baselineAssembler.assemble(any())).thenAnswer(inv -> assembled(HASH_A, true, List.of()));

        BaselineResponse resp = service.freeze(PROJECT, HASH_A);

        assertThat(resp.getId()).isEqualTo(700L);
        verify(baselineSnapshotMapper, never()).insert(any());
        verify(baselineSnapshotMapper, never()).updateById(any());
        verify(designProjectMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("内容变更后冻结：新快照 v2 ACTIVE，旧快照 SUPERSEDED 且回填 superseded_by")
    void changedContentSupersedesOld() {
        BaselineSnapshot active = activeSnapshot(700L, 1, HASH_A);
        when(baselineSnapshotMapper.selectOne(any())).thenReturn(active);
        when(baselineAssembler.assemble(any())).thenAnswer(inv -> assembled(HASH_B, true, List.of()));

        BaselineResponse resp = service.freeze(PROJECT, HASH_B);

        ArgumentCaptor<BaselineSnapshot> inserted = ArgumentCaptor.forClass(BaselineSnapshot.class);
        verify(baselineSnapshotMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getProjectVersion()).isEqualTo(2);
        assertThat(inserted.getValue().getStatus()).isEqualTo("ACTIVE");

        ArgumentCaptor<BaselineSnapshot> patched = ArgumentCaptor.forClass(BaselineSnapshot.class);
        verify(baselineSnapshotMapper).updateById(patched.capture());
        assertThat(patched.getValue().getId()).isEqualTo(700L);
        assertThat(patched.getValue().getStatus()).isEqualTo("SUPERSEDED");
        assertThat(patched.getValue().getSupersededBy()).isEqualTo(555L);

        assertThat(resp.getProjectVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("不传 expectedContentHash：跳过并发校验，正常冻结（前端首次提交的路径）")
    void freezeWithoutExpectedHashSkipsStaleCheck() {
        when(baselineAssembler.assemble(any())).thenAnswer(inv -> assembled(HASH_A, true, List.of()));

        assertThat(service.freeze(PROJECT, null).getProjectVersion()).isEqualTo(1);
        verify(baselineSnapshotMapper).insert(any());
    }

    // ------------------------------------------------------------------ 查询

    @Test
    @DisplayName("没有冻结过基线：current 抛 BASELINE_NOT_FOUND，不返回空壳")
    void currentWhenNoneFrozen() {
        when(baselineSnapshotMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.current(PROJECT))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BASELINE_NOT_FOUND);
    }

    @Test
    @DisplayName("历史：按版本号倒序返回（v2 在前，v1 在后）")
    void historyKeepsOrder() {
        when(baselineSnapshotMapper.selectList(any()))
                .thenReturn(List.of(activeSnapshot(701L, 2, HASH_B), activeSnapshot(700L, 1, HASH_A)));

        List<BaselineResponse> list = service.history(PROJECT);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).getProjectVersion()).isEqualTo(2);
        assertThat(list.get(1).getProjectVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("preview 只读：不改库，直接透出装配结果")
    void previewIsReadOnly() {
        when(baselineAssembler.assemble(any())).thenAnswer(inv -> assembled(HASH_A, false, List.of("缺靶点")));

        BaselinePreviewResponse preview = service.preview(PROJECT);

        assertThat(preview.isReady()).isFalse();
        assertThat(preview.getGaps()).hasSize(1);
        verify(baselineSnapshotMapper, never()).insert(any());
        verify(designProjectMapper, never()).updateById(any());
    }
}
