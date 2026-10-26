package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.DesignCommandResponse;
import com.synpharm.design.api.dto.WorkspaceStateResponse;
import com.synpharm.design.api.dto.WorkspaceStateUpsertRequest;
import com.synpharm.design.application.WorkspaceAppService;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.ProjectStatus;
import com.synpharm.design.domain.model.WorkspaceState;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.WorkspaceStateMapper;
import com.synpharm.platform.api.dto.ToolRegistryItem;
import com.synpharm.platform.application.ToolAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 工作站应用服务实现。
 *
 * <p><b>`project_id` 为 NULL 时的写入必须格外小心</b>：{@code uk_user_project(user_id, project_id)}
 * 在 MySQL 下**不约束 NULL**（唯一索引视多个 NULL 为互不相同），所以
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} 对「用户级最近项目」这一行不会命中，
 * 直接插会产生一堆同义行。因此这里先按 {@code last_visited_at} 取最近一行再决定
 * 更新或插入 —— 这也是 DDL 注释里「同一用户可以有多行 project_id IS NULL 的历史记录，
 * 取 last_visited_at 最大者」所要求的读法。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceAppServiceImpl implements WorkspaceAppService {

    /** 检索类命令不改变项目状态，不算写操作（详见 {@link DesignCommandResponse} 的推导说明） */
    private static final String READ_ONLY_CATEGORY = "RETRIEVAL";

    private final WorkspaceStateMapper workspaceStateMapper;
    private final DesignProjectMapper designProjectMapper;
    // 工具注册表已同进程（原 platform 服务合并进 core），不再走 HTTP
    private final ToolAppService toolAppService;
    private final JsonCodec jsonCodec;

    @Override
    public WorkspaceStateResponse get(Long projectId) {
        Long userId = CurrentUser.requireUserId();
        WorkspaceState state = findLatest(userId, projectId);
        if (state == null) {
            // 没保存过 ≠ 资源不存在：视图状态是"有没有记忆"，前端用 `?? 'canvas'` 兜底
            return WorkspaceStateResponse.builder()
                    .projectId(projectId)
                    .openMolIds(List.of())
                    .build();
        }
        return toResponse(state);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(WorkspaceStateUpsertRequest request) {
        Long userId = CurrentUser.requireUserId();
        WorkspaceState existing = findLatest(userId, request.getProjectId());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            WorkspaceState state = new WorkspaceState();
            state.setUserId(userId);
            state.setProjectId(request.getProjectId());
            state.setLastRoundId(request.getLastRoundId());
            state.setLastMolId(request.getLastMolId());
            state.setActiveTab(request.getActiveTab());
            state.setOpenMolIds(request.getOpenMolIds() == null ? null : jsonCodec.write(request.getOpenMolIds()));
            state.setPanelLayoutJson(request.getPanelLayout() == null ? null : jsonCodec.write(request.getPanelLayout()));
            state.setLastVisitedAt(now);
            state.setDeleted(0);
            workspaceStateMapper.insert(state);
            return;
        }

        // 用 UpdateWrapper 逐字段显式 set：实现「不传 = 不变」，同时避免把未提供的字段写成 null
        UpdateWrapper<WorkspaceState> update = new UpdateWrapper<WorkspaceState>().eq("id", existing.getId());
        if (request.getLastRoundId() != null) {
            update.set("last_round_id", request.getLastRoundId());
        }
        if (request.getLastMolId() != null) {
            update.set("last_mol_id", request.getLastMolId());
        }
        if (request.getActiveTab() != null) {
            update.set("active_tab", request.getActiveTab());
        }
        if (request.getOpenMolIds() != null) {
            update.set("open_mol_ids", jsonCodec.write(request.getOpenMolIds()));
        }
        if (request.getPanelLayout() != null) {
            update.set("panel_layout_json", jsonCodec.write(request.getPanelLayout()));
        }
        update.set("last_visited_at", now);
        workspaceStateMapper.update(null, update);
    }

    @Override
    public List<DesignCommandResponse> commands(Long projectId) {
        Long userId = CurrentUser.requireUserId();
        String blockedReason = projectBlockedReason(projectId, userId);

        return toolAppService.registry().stream()
                .map(entry -> toCommand(entry, blockedReason))
                .toList();
    }

    /**
     * 项目状态对写命令的影响。
     *
     * <p>契约只写了「按项目状态 + 能力过滤」而没给细则，这里取**唯一没有争议**的一条：
     * 已归档的项目不允许再产生新的写操作（它已经结束，任何生成/编辑/评估都是脏数据）。
     * 其余状态（DRAFT/READY/ACTIVE/COMPLETED/CANCELLED）暂不限制 —— 补规则前请先确认设计意图。
     */
    private String projectBlockedReason(Long projectId, Long userId) {
        if (projectId == null) {
            return null;
        }
        DesignProject project = designProjectMapper.selectById(projectId);
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", projectId));
        if (ProjectStatus.ARCHIVED.name().equals(project.getStatus())) {
            return "项目已归档，不可再执行写操作";
        }
        return null;
    }

    private DesignCommandResponse toCommand(ToolRegistryItem entry, String blockedReason) {
        boolean writes = !READ_ONLY_CATEGORY.equals(entry.getCategory());
        boolean available = Boolean.TRUE.equals(entry.getAvailable())
                && Boolean.TRUE.equals(entry.getEnabled());
        String reason = entry.getProbeMsg();

        if (available && writes && blockedReason != null) {
            available = false;
            reason = blockedReason;
        }
        return DesignCommandResponse.builder()
                .id(entry.getToolCode())
                .label(entry.getName())
                .group(entry.getCategory())
                .writes(writes)
                .available(available)
                .reason(reason)
                .realness(entry.getRealness())
                .build();
    }

    /** 取最近一条状态：NULL 项目槽位下可能有多行历史，必须按 last_visited_at 取最大者 */
    private WorkspaceState findLatest(Long userId, Long projectId) {
        LambdaQueryWrapper<WorkspaceState> query = new LambdaQueryWrapper<WorkspaceState>()
                .eq(WorkspaceState::getUserId, userId)
                .orderByDesc(WorkspaceState::getLastVisitedAt)
                .last("LIMIT 1");
        if (projectId == null) {
            query.isNull(WorkspaceState::getProjectId);
        } else {
            query.eq(WorkspaceState::getProjectId, projectId);
        }
        return workspaceStateMapper.selectOne(query);
    }

    private WorkspaceStateResponse toResponse(WorkspaceState state) {
        Long[] molIds = jsonCodec.read(state.getOpenMolIds(), Long[].class);
        return WorkspaceStateResponse.builder()
                .projectId(state.getProjectId())
                .lastRoundId(state.getLastRoundId())
                .lastMolId(state.getLastMolId())
                .activeTab(state.getActiveTab())
                .openMolIds(molIds == null ? List.of() : Arrays.asList(molIds))
                .panelLayout(jsonCodec.readMap(state.getPanelLayoutJson()))
                .lastVisitedAt(state.getLastVisitedAt())
                .build();
    }
}
