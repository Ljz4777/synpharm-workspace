package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.DesignCommandResponse;
import com.synpharm.design.api.dto.WorkspaceStateResponse;
import com.synpharm.design.api.dto.WorkspaceStateUpsertRequest;
import com.synpharm.design.application.WorkspaceAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作站端点（契约 4.3.12：视图状态、命令注册表）。
 *
 * <p>命令注册表是**写操作的唯一入口**：未部署引擎对应命令必须置灰并给出原因，
 * 因此它的数据来自 platform 的工具注册表（服务间调用）而不是本服务的表。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceAppService workspaceAppService;

    /** 工作台视图状态（IDE 恢复）；未保存过返回全空对象 */
    @GetMapping("/workspace")
    public Result<WorkspaceStateResponse> getWorkspace(
            @RequestParam(required = false) Long projectId) {
        return Result.success(workspaceAppService.get(projectId));
    }

    /** 保存工作台视图状态；不传的字段保持不变 */
    @PutMapping("/workspace")
    public Result<Void> saveWorkspace(@Valid @RequestBody WorkspaceStateUpsertRequest request) {
        workspaceAppService.save(request);
        return Result.success(null);
    }

    /** 命令注册表（按能力与项目状态置灰） */
    @GetMapping("/commands")
    public Result<List<DesignCommandResponse>> listCommands(
            @RequestParam(required = false) Long projectId) {
        return Result.success(workspaceAppService.commands(projectId));
    }
}
