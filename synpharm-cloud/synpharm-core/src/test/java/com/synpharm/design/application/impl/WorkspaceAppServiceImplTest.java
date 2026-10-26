package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUserTestSupport;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.DesignCommandResponse;
import com.synpharm.design.api.dto.WorkspaceStateResponse;
import com.synpharm.design.api.dto.WorkspaceStateUpsertRequest;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.WorkspaceState;
import com.synpharm.platform.api.dto.ToolRegistryItem;
import com.synpharm.platform.application.ToolAppService;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.WorkspaceStateMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作站与命令注册表测试。
 *
 * <p>锁死三条容易写错、出错后又不显眼的规则：
 * <ol>
 *   <li><b>{@code project_id IS NULL} 的槽位必须"先读最近一行再决定写入"</b> ——
 *       {@code uk_user_project(user_id, project_id)} 在 MySQL 下不约束 NULL，
 *       直接 INSERT 会攒出一堆同义行，而"最近项目"从此取哪一行都说不清。</li>
 *   <li><b>省略字段 = 不变</b>（与 design 域其它 PATCH 相反）：UPDATE 语句里不能出现
 *       本次请求没提供的列，否则一次只改 activeTab 的保存会把打开的分子页签抹掉。</li>
 *   <li><b>命令置灰只在点击前</b>（红线 R1/R2）：引擎不可用时命令必须
 *       {@code available=false} 并带可读原因；已归档项目上的写命令另有一条原因。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceAppServiceImplTest {

    private static final long ME = 100L;
    private static final long PROJECT = 700L;

    @Mock private WorkspaceStateMapper workspaceStateMapper;
    @Mock private DesignProjectMapper designProjectMapper;
    @Mock private ToolAppService toolAppService;

    private WorkspaceAppServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceAppServiceImpl(workspaceStateMapper, designProjectMapper,
                toolAppService, new JsonCodec(new ObjectMapper()));
        // 条件构造器的列名来自 lambda 缓存，纯单测里必须先登记实体的表信息，否则解析直接抛
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WorkspaceState.class);
        CurrentUserTestSupport.login(ME);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.logout();
    }

    // ------------------------------------------------------------------ 读

    @Test
    @DisplayName("没保存过：返回全空对象而不是 404，openMolIds 是空列表不是 null")
    void returnsEmptyWhenNeverSaved() {
        when(workspaceStateMapper.selectOne(any())).thenReturn(null);

        WorkspaceStateResponse response = service.get(PROJECT);

        assertThat(response.getProjectId()).isEqualTo(PROJECT);
        assertThat(response.getActiveTab()).isNull();
        assertThat(response.getLastRoundId()).isNull();
        assertThat(response.getPanelLayout()).isNull();
        // 前端直接 .map()/.length 用它，null 会炸
        assertThat(response.getOpenMolIds()).isEmpty();
    }

    @Test
    @DisplayName("读「最近项目」槽位：只查 project_id IS NULL 且按 last_visited_at 取最新一行")
    void readsLatestRowOfUserLevelSlot() {
        when(workspaceStateMapper.selectOne(any())).thenReturn(null);

        service.get(null);

        ArgumentCaptor<Wrapper<WorkspaceState>> captor = queryCaptor();
        verify(workspaceStateMapper).selectOne(captor.capture());
        String sql = asWrapper(captor.getValue()).getSqlSegment();
        assertThat(sql).contains("project_id").contains("IS NULL");
        assertThat(sql).contains("ORDER BY last_visited_at DESC");
        assertThat(sql).contains("LIMIT 1");
    }

    @Test
    @DisplayName("已保存：openMolIds 与 panelLayout 的 JSON 往返还原")
    void roundTripsJsonColumns() {
        WorkspaceState stored = new WorkspaceState();
        stored.setId(1L);
        stored.setUserId(ME);
        stored.setProjectId(PROJECT);
        stored.setActiveTab("pose");
        stored.setLastRoundId(11L);
        stored.setOpenMolIds("[7,8,9]");
        stored.setPanelLayoutJson("{\"left\":240}");
        stored.setLastVisitedAt(LocalDateTime.now());
        when(workspaceStateMapper.selectOne(any())).thenReturn(stored);

        WorkspaceStateResponse response = service.get(PROJECT);

        assertThat(response.getOpenMolIds()).containsExactly(7L, 8L, 9L);
        assertThat(response.getPanelLayout()).containsEntry("left", 240);
        assertThat(response.getActiveTab()).isEqualTo("pose");
    }

    // ------------------------------------------------------------------ 写

    @Test
    @DisplayName("首次保存用户级槽位：INSERT，且落 lastVisitedAt 与 deleted=0")
    void insertsWhenNoRowExists() {
        when(workspaceStateMapper.selectOne(any())).thenReturn(null);
        WorkspaceStateUpsertRequest request = new WorkspaceStateUpsertRequest();
        request.setActiveTab("canvas");
        request.setOpenMolIds(List.of(3L, 4L));

        service.save(request);

        ArgumentCaptor<WorkspaceState> captor = ArgumentCaptor.forClass(WorkspaceState.class);
        verify(workspaceStateMapper).insert(captor.capture());
        WorkspaceState inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(ME);
        assertThat(inserted.getProjectId()).isNull();
        assertThat(inserted.getOpenMolIds()).isEqualTo("[3,4]");
        assertThat(inserted.getLastVisitedAt()).isNotNull();
        assertThat(inserted.getDeleted()).isZero();
        verify(workspaceStateMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("用户级槽位已有行：UPDATE 而不是 INSERT（避免 uk_user_project 对 NULL 失效攒出同义行）")
    void updatesInsteadOfInsertingForUserLevelSlot() {
        WorkspaceState existing = new WorkspaceState();
        existing.setId(42L);
        existing.setUserId(ME);
        existing.setProjectId(null);
        existing.setLastVisitedAt(LocalDateTime.now().minusDays(3));
        when(workspaceStateMapper.selectOne(any())).thenReturn(existing);

        WorkspaceStateUpsertRequest request = new WorkspaceStateUpsertRequest();
        request.setActiveTab("evaluation");

        service.save(request);

        ArgumentCaptor<Wrapper<WorkspaceState>> captor = updateCaptor();
        verify(workspaceStateMapper).update(isNull(), captor.capture());
        AbstractWrapper<WorkspaceState, ?, ?> update = asWrapper(captor.getValue());
        // WHERE 必须落在已有那一行上（id=42），否则又会插出新行
        assertThat(update.getSqlSegment()).contains("id");
        assertThat(update.getParamNameValuePairs()).containsValue(42L);
        verify(workspaceStateMapper, never()).insert(any());
    }

    @Test
    @DisplayName("省略的字段不进 UPDATE 语句（不传 = 不变）")
    void omittedFieldsAreNotTouched() {
        WorkspaceState existing = new WorkspaceState();
        existing.setId(42L);
        existing.setUserId(ME);
        when(workspaceStateMapper.selectOne(any())).thenReturn(existing);

        WorkspaceStateUpsertRequest request = new WorkspaceStateUpsertRequest();
        request.setLastMolId(9L);

        service.save(request);

        String sqlSet = capturedSqlSet();
        assertThat(sqlSet).contains("last_mol_id");
        assertThat(sqlSet).contains("last_visited_at");
        assertThat(sqlSet).doesNotContain("active_tab");
        assertThat(sqlSet).doesNotContain("open_mol_ids");
        assertThat(sqlSet).doesNotContain("panel_layout_json");
        assertThat(sqlSet).doesNotContain("last_round_id");
    }

    @Test
    @DisplayName("panelLayout 传空对象：算「已提供」，必须写入（只有 null 才是「不传」）")
    void emptyPanelLayoutIsStillAnUpdate() {
        WorkspaceState existing = new WorkspaceState();
        existing.setId(42L);
        existing.setUserId(ME);
        when(workspaceStateMapper.selectOne(any())).thenReturn(existing);

        WorkspaceStateUpsertRequest request = new WorkspaceStateUpsertRequest();
        request.setPanelLayout(Map.of());

        service.save(request);

        String sqlSet = capturedSqlSet();
        assertThat(sqlSet).contains("panel_layout_json");
        assertThat(sqlSet).doesNotContain("open_mol_ids");
    }

    // ------------------------------------------------------- 命令注册表

    @Test
    @DisplayName("命令映射：id←toolCode、label←name、group←category；检索类不算写操作")
    void mapsRegistryToCommands() {
        when(toolAppService.registry()).thenReturn(List.of(
                entry("mol_properties", "分子理化性质", "EVALUATION", true, true, null),
                entry("target_profile", "靶点检索", "RETRIEVAL", true, true, null)));

        List<DesignCommandResponse> commands = service.commands(null);

        assertThat(commands).hasSize(2);
        DesignCommandResponse write = commands.get(0);
        assertThat(write.getId()).isEqualTo("mol_properties");
        assertThat(write.getLabel()).isEqualTo("分子理化性质");
        assertThat(write.getGroup()).isEqualTo("EVALUATION");
        assertThat(write.getWrites()).isTrue();
        assertThat(write.getAvailable()).isTrue();
        assertThat(write.getReason()).isNull();

        assertThat(commands.get(1).getWrites()).isFalse();
    }

    @Test
    @DisplayName("引擎未部署：命令置灰并带平台给的原因（红线 R2：不「点了才失败」）")
    void greysOutUnavailableCommand() {
        when(toolAppService.registry()).thenReturn(List.of(
                entry("pocket_detect", "口袋检测", "PREDICTION", false, true, "引擎 p2rank 未部署")));

        DesignCommandResponse command = service.commands(null).get(0);

        assertThat(command.getAvailable()).isFalse();
        assertThat(command.getReason()).isEqualTo("引擎 p2rank 未部署");
    }

    @Test
    @DisplayName("工具被禁用（enabled=false）也要置灰，即使能力探测结果是可用")
    void greysOutDisabledCommand() {
        when(toolAppService.registry()).thenReturn(List.of(
                entry("mol_properties", "分子理化性质", "EVALUATION", true, false, null)));

        assertThat(service.commands(null).get(0).getAvailable()).isFalse();
    }

    @Test
    @DisplayName("已归档项目：写命令不可用且原因是归档，检索类命令不受影响")
    void archivedProjectBlocksOnlyWriteCommands() {
        DesignProject project = new DesignProject();
        project.setId(PROJECT);
        project.setUserId(ME);
        project.setStatus("ARCHIVED");
        when(designProjectMapper.selectById(PROJECT)).thenReturn(project);
        when(toolAppService.registry()).thenReturn(List.of(
                entry("mol_edit", "分子编辑", "GENERATION", true, true, null),
                entry("target_profile", "靶点检索", "RETRIEVAL", true, true, null)));

        List<DesignCommandResponse> commands = service.commands(PROJECT);

        assertThat(commands.get(0).getAvailable()).isFalse();
        assertThat(commands.get(0).getReason()).isEqualTo("项目已归档，不可再执行写操作");
        // 检索不改状态，归档项目照样能查
        assertThat(commands.get(1).getAvailable()).isTrue();
        assertThat(commands.get(1).getReason()).isNull();
    }

    @Test
    @DisplayName("不传 projectId：那是「最近项目」上下文，不能去查项目表")
    void doesNotLoadProjectWhenProjectIdAbsent() {
        when(toolAppService.registry()).thenReturn(List.of(
                entry("mol_edit", "分子编辑", "GENERATION", true, true, null)));

        assertThat(service.commands(null).get(0).getAvailable()).isTrue();
        verify(designProjectMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("项目不属于当前用户：404 DESIGN_PROJECT_NOT_FOUND（不泄露项目是否存在）")
    void rejectsForeignProject() {
        assertThatThrownBy(() -> service.commands(PROJECT))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DESIGN_PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("platform 不可达：503 原样冒泡，不降级成空命令列表")
    void propagatesRegistryFailure() {
        when(toolAppService.registry())
                .thenThrow(new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "工具能力服务不可用，请稍后重试"));

        assertThatThrownBy(() -> service.commands(null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    // ------------------------------------------------------------- helpers

    private static ToolRegistryItem entry(String code, String name, String category,
                                           boolean available, boolean enabled, String probeMsg) {
        ToolRegistryItem entry = new ToolRegistryItem();
        entry.setToolCode(code);
        entry.setName(name);
        entry.setCategory(category);
        entry.setRealness("REAL");
        entry.setAvailable(available);
        entry.setEnabled(enabled);
        entry.setProbeMsg(probeMsg);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<WorkspaceState>> queryCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<WorkspaceState>> updateCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static AbstractWrapper<WorkspaceState, ?, ?> asWrapper(Wrapper<WorkspaceState> wrapper) {
        return (AbstractWrapper<WorkspaceState, ?, ?>) wrapper;
    }

    private String capturedSqlSet() {
        ArgumentCaptor<Wrapper<WorkspaceState>> captor = updateCaptor();
        verify(workspaceStateMapper).update(isNull(), captor.capture());
        Wrapper<WorkspaceState> wrapper = captor.getValue();
        assertThat(wrapper).isInstanceOf(UpdateWrapper.class);
        // 条件构造器的取值是惰性求值的：先取 SQL 片段，参数表才会被填上
        ((UpdateWrapper<WorkspaceState>) wrapper).getSqlSegment();
        String sqlSet = ((UpdateWrapper<WorkspaceState>) wrapper).getSqlSet();
        assertThat(sqlSet).isNotNull();
        return sqlSet;
    }
}
