package com.synpharm.platform.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.platform.api.dto.CapabilitiesResponse;
import com.synpharm.platform.api.dto.ToolRegistryItem;
import com.synpharm.platform.api.dto.ToolResponse;
import com.synpharm.platform.api.dto.ToolUpsertRequest;

import java.util.List;

/**
 * 工具注册表应用服务（契约 4.3.11 的 6 个端点）。
 *
 * @author SynPharm Team
 */
public interface ToolAppService {

    /** 工具列表（可按分类 / 启用状态过滤，分页） */
    PageResult<ToolResponse> list(String category, Boolean enabled, int page, int pageSize);

    /** 工具详情 */
    ToolResponse get(Long id);

    /** 注册工具；toolCode 重复返回 409 */
    ToolResponse create(ToolUpsertRequest request);

    /** 更新工具元数据；可用性字段不接受修改 */
    ToolResponse patch(Long id, ToolUpsertRequest request);

    /** 能力探测并回写 */
    ToolResponse probe(Long id);

    /** 当前能力矩阵 */
    CapabilitiesResponse capabilities();

    /** 供服务间调用（design 的命令注册表）的注册表条目 */
    List<ToolRegistryItem> registry();
}