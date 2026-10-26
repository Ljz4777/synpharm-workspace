package com.synpharm.platform.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.web.PageResult;
import com.synpharm.platform.api.dto.CapabilitiesResponse;
import com.synpharm.platform.api.dto.ToolResponse;
import com.synpharm.platform.api.dto.ToolUpsertRequest;
import com.synpharm.platform.application.ToolAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具与能力端点（契约 4.3.11，6 个操作）。
 *
 * <p><b>路径在 {@code /api/design/**} 之下但服务归 platform</b>：路径前缀表达的是
 * 前端功能域（设计工作台），数据所有权才决定服务归属 —— {@code design_tool} 表归
 * platform，故网关把 {@code /api/design/tools/**} 与 {@code /api/design/capabilities}
 * 单独路由到本服务（见规范 §1.1、勘误 C-05）。这个「路径与归属分离」的约定容易被误读成漏配路由，
 * 改动网关时务必保留这两条独立路由。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
public class ToolController {

    private final ToolAppService toolAppService;

    /** 工具列表 */
    @GetMapping("/tools")
    public Result<PageResult<ToolResponse>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(toolAppService.list(category, enabled, page, pageSize));
    }

    /** 注册工具；{@code realness} 必填，{@code available} 不接受传入 */
    @PostMapping("/tools")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<ToolResponse> create(@Valid @RequestBody ToolUpsertRequest request) {
        return Result.success(toolAppService.create(request));
    }

    /** 工具详情 */
    @GetMapping("/tools/{id}")
    public Result<ToolResponse> get(@PathVariable Long id) {
        return Result.success(toolAppService.get(id));
    }

    /** 更新工具（启用/禁用/暴露给智能体等）；不传的字段保持不变 */
    @PatchMapping("/tools/{id}")
    public Result<ToolResponse> patch(@PathVariable Long id,
                                      @RequestBody ToolUpsertRequest request) {
        return Result.success(toolAppService.patch(id, request));
    }

    /** 能力探测（回写 available / probe_msg）；探测本身失败返回 502 */
    @PostMapping("/tools/{id}/probe")
    public Result<ToolResponse> probe(@PathVariable Long id) {
        return Result.success(toolAppService.probe(id));
    }

    /**
     * 当前能力矩阵（前端能力状态灯）。
     *
     * <p><b>登录校验为什么在 controller 而不是 AppService</b>：同一个
     * {@link ToolAppService#capabilities()} 还被 {@code InternalToolController} 的
     * {@code /internal/tools/capabilities} 复用，那条是 design 的服务间调用、**没有用户身份**。
     * 把这行校验下沉到 AppService 会直接打断 design 的命令注册表（见问题总账 H-27）。
     */
    @GetMapping("/capabilities")
    public Result<CapabilitiesResponse> capabilities() {
        CurrentUser.requireUserId();
        return Result.success(toolAppService.capabilities());
    }
}