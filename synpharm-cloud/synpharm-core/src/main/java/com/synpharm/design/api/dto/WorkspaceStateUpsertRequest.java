package com.synpharm.design.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 工作台视图状态保存请求（契约 `WorkspaceStateUpsert`）。
 *
 * <p><b>语义：不传 = 不变</b>（与 design 域其它 PATCH 的「不传 = 清空」相反）。
 * 理由：接口虽然写作 PUT，但前端发的是 `Partial<WorkspaceState>`，而视图状态是
 * 「UI 自己的记忆」—— 若按"省略即清空"处理，一次只改 activeTab 的保存就会把
 * 已打开的分子页签与面板布局一起抹掉。代价是无法用省略来清空某个字段，对视图状态可接受。
 *
 * @author SynPharm Team
 */
@Data
public class WorkspaceStateUpsertRequest {

    /** null = 用户级「最近项目」槽位 */
    private Long projectId;

    private Long lastRoundId;

    private Long lastMolId;

    @Size(max = 30, message = "activeTab 最长 30 字符")
    private String activeTab;

    private List<Long> openMolIds;

    private Map<String, Object> panelLayout;
}
