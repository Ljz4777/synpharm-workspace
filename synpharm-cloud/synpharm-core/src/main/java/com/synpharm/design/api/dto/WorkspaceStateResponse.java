package com.synpharm.design.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作台视图状态（契约 `GET /api/design/workspace`）。
 *
 * <p>未保存过时返回**全空对象**而不是 404：视图状态是「有没有历史记忆」的问题，
 * 不是「资源存不存在」的问题；前端 `workspace.activeTab ?? 'canvas'` 也正是这么用的。
 *
 * @author SynPharm Team
 */
@Data
@Builder
public class WorkspaceStateResponse {

    private Long projectId;

    private Long lastRoundId;

    private Long lastMolId;

    private String activeTab;

    private List<Long> openMolIds;

    private Map<String, Object> panelLayout;

    private LocalDateTime lastVisitedAt;
}
