package com.synpharm.design.application;

import com.synpharm.design.api.dto.DesignCommandResponse;
import com.synpharm.design.api.dto.WorkspaceStateResponse;
import com.synpharm.design.api.dto.WorkspaceStateUpsertRequest;

import java.util.List;

/**
 * 工作站应用服务（契约 4.3.12：视图状态、命令注册表；对比见 {@code compare} 端点）。
 *
 * @author SynPharm Team
 */
public interface WorkspaceAppService {

    /** 读取工作台视图状态；未保存过时返回全空对象（不是 404） */
    WorkspaceStateResponse get(Long projectId);

    /** 保存工作台视图状态（不传 = 不变） */
    void save(WorkspaceStateUpsertRequest request);

    /** 命令注册表（数据源为 platform 的工具注册表，按能力与项目状态过滤） */
    List<DesignCommandResponse> commands(Long projectId);
}
