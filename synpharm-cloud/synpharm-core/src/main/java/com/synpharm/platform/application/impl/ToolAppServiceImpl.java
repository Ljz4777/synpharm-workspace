package com.synpharm.platform.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.platform.api.dto.CapabilitiesResponse;
import com.synpharm.platform.api.dto.ToolRegistryItem;
import com.synpharm.platform.api.dto.ToolResponse;
import com.synpharm.platform.api.dto.ToolUpsertRequest;
import com.synpharm.platform.application.ToolAppService;
import com.synpharm.platform.domain.model.DesignTool;
import com.synpharm.platform.infrastructure.persistence.DesignToolMapper;
import com.synpharm.platform.support.ToolRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 工具注册表应用服务实现。
 *
 * <p><b>三条不能破的约束</b>：
 * <ol>
 *   <li>{@code available} / {@code probe_msg} / {@code last_probe_at} 是探测产物，
 *       <b>任何</b>写接口都不碰它们（{@code POST} 落默认值、{@code PATCH} 显式排除）；
 *       否则等于允许伪造能力（红线 R1）。</li>
 *   <li>新注册的工具必须 {@code available=0} + {@code probeMsg="尚未探测"} ——
 *       建表默认 0 的语义是「未探测 ≠ 可用」，缺了说明文案会让界面显示一个没有原因的红点。</li>
 *   <li>写接口仅限管理员：工具元数据里的 {@code endpoint} 会被 design 的命令注册表**直接调用**，
 *       若任何登录用户都能改，等于开了一个可以指向任意内网地址的入口。</li>
 * </ol>
 *
 * <p><b>PATCH 语义：不传 = 不变</b>（与 design 域「不传 = 清空」相反，这是刻意的）。
 * 契约把 {@code PATCH /tools/{id}} 的用途写成「更新（启用/禁用/暴露给智能体）」，即开关类操作；
 * 且请求体 schema 复用了 {@code ToolUpsert}，其必填字段在库里是 NOT NULL，
 * 若按「不传 = 清空」处理，客户端只想关掉一个工具也得回传全部字段，否则直接违反非空约束。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAppServiceImpl implements ToolAppService {

    private final DesignToolMapper designToolMapper;
    private final ToolRegistryService toolRegistryService;
    private final JsonCodec jsonCodec;

    @Override
    public PageResult<ToolResponse> list(String category, Boolean enabled, int page, int pageSize) {
        requireLogin();
        LambdaQueryWrapper<DesignTool> query = new LambdaQueryWrapper<DesignTool>()
                .eq(StringUtils.hasText(category), DesignTool::getCategory, category)
                .eq(enabled != null, DesignTool::getEnabled, enabled == null ? null : (enabled ? 1 : 0))
                .orderByAsc(DesignTool::getToolCode);
        Page<DesignTool> result = designToolMapper.selectPage(new Page<>(page, pageSize), query);
        return PageResult.of(result, this::toResponse);
    }

    @Override
    public ToolResponse get(Long id) {
        requireLogin();
        return toResponse(require(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ToolResponse create(ToolUpsertRequest request) {
        requireAdmin();
        Long existing = designToolMapper.selectCount(new LambdaQueryWrapper<DesignTool>()
                .eq(DesignTool::getToolCode, request.getToolCode()));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.TOOL_CODE_DUPLICATED, "工具码已存在：" + request.getToolCode());
        }

        DesignTool tool = new DesignTool();
        tool.setToolCode(request.getToolCode());
        tool.setName(request.getName());
        tool.setDescription(request.getDescription());
        tool.setCategory(request.getCategory().name());
        tool.setType(defaultIfNull(request.getType(), com.synpharm.platform.domain.model.ToolType.HTTP).name());
        tool.setProvider(defaultIfNull(request.getProvider(), com.synpharm.platform.domain.model.ToolProvider.FASTAPI).name());
        tool.setEndpoint(request.getEndpoint());
        tool.setHttpMethod(defaultIfNull(request.getHttpMethod(), "POST"));
        tool.setInputSchema(request.getInputSchema() == null ? null : jsonCodec.write(request.getInputSchema()));
        tool.setOutputSchema(request.getOutputSchema() == null ? null : jsonCodec.write(request.getOutputSchema()));
        tool.setAsyncExecution(boolToTinyInt(defaultIfNull(request.getAsyncExecution(), Boolean.TRUE)));
        tool.setTimeoutMs(defaultIfNull(request.getTimeoutMs(), 60000));
        tool.setRequiresEngine(request.getRequiresEngine());
        tool.setCapabilityKey(request.getCapabilityKey());
        tool.setRealness(request.getRealness().name());
        tool.setEnabled(boolToTinyInt(defaultIfNull(request.getEnabled(), Boolean.TRUE)));
        tool.setAgentExposed(boolToTinyInt(defaultIfNull(request.getAgentExposed(), Boolean.FALSE)));
        tool.setAgentToolName(request.getAgentToolName());
        tool.setVersion(request.getVersion());
        // 能力事实一律落到「未探测」，绝不允许客户端传入
        tool.setAvailable(0);
        tool.setProbeMsg("尚未探测");
        tool.setLastProbeAt(null);
        tool.setDeleted(0);

        try {
            designToolMapper.insert(tool);
        } catch (DuplicateKeyException e) {
            // 并发下的兜底：唯一键 uk_tool_code 兜住竞态
            throw new BizException(ErrorCode.TOOL_CODE_DUPLICATED, "工具码已存在：" + request.getToolCode());
        }
        log.info("注册工具 toolCode={} category={} realness={}",
                tool.getToolCode(), tool.getCategory(), tool.getRealness());
        return toResponse(tool);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ToolResponse patch(Long id, ToolUpsertRequest request) {
        requireAdmin();
        require(id);
        if (StringUtils.hasText(request.getToolCode())) {
            Long duplicated = designToolMapper.selectCount(new LambdaQueryWrapper<DesignTool>()
                    .eq(DesignTool::getToolCode, request.getToolCode())
                    .ne(DesignTool::getId, id));
            if (duplicated != null && duplicated > 0) {
                throw new BizException(ErrorCode.TOOL_CODE_DUPLICATED, "工具码已存在：" + request.getToolCode());
            }
        }

        // 用 UpdateWrapper 逐字段显式 set：既实现「不传 = 不变」，
        // 又保证 available / probe_msg / last_probe_at 一个字节都不会被覆盖
        UpdateWrapper<DesignTool> update = new UpdateWrapper<DesignTool>().eq("id", id);
        if (StringUtils.hasText(request.getToolCode())) {
            update.set("tool_code", request.getToolCode());
        }
        if (StringUtils.hasText(request.getName())) {
            update.set("name", request.getName());
        }
        if (request.getDescription() != null) {
            update.set("description", request.getDescription());
        }
        if (request.getCategory() != null) {
            update.set("category", request.getCategory().name());
        }
        if (request.getType() != null) {
            update.set("type", request.getType().name());
        }
        if (request.getProvider() != null) {
            update.set("provider", request.getProvider().name());
        }
        if (request.getEndpoint() != null) {
            update.set("endpoint", request.getEndpoint());
        }
        if (StringUtils.hasText(request.getHttpMethod())) {
            update.set("http_method", request.getHttpMethod());
        }
        if (request.getInputSchema() != null) {
            update.set("input_schema", jsonCodec.write(request.getInputSchema()));
        }
        if (request.getOutputSchema() != null) {
            update.set("output_schema", jsonCodec.write(request.getOutputSchema()));
        }
        if (request.getAsyncExecution() != null) {
            update.set("async_execution", boolToTinyInt(request.getAsyncExecution()));
        }
        if (request.getTimeoutMs() != null) {
            update.set("timeout_ms", request.getTimeoutMs());
        }
        if (request.getRequiresEngine() != null) {
            update.set("requires_engine", request.getRequiresEngine());
        }
        if (request.getCapabilityKey() != null) {
            update.set("capability_key", request.getCapabilityKey());
        }
        if (request.getRealness() != null) {
            update.set("realness", request.getRealness().name());
        }
        if (request.getEnabled() != null) {
            update.set("enabled", boolToTinyInt(request.getEnabled()));
        }
        if (request.getAgentExposed() != null) {
            update.set("agent_exposed", boolToTinyInt(request.getAgentExposed()));
        }
        if (request.getAgentToolName() != null) {
            update.set("agent_tool_name", request.getAgentToolName());
        }
        if (request.getVersion() != null) {
            update.set("version", request.getVersion());
        }
        designToolMapper.update(null, update);
        return toResponse(require(id));
    }

    @Override
    public ToolResponse probe(Long id) {
        requireAdmin();
        DesignTool tool = require(id);
        return toResponse(toolRegistryService.probe(tool));
    }

    @Override
    public CapabilitiesResponse capabilities() {
        return toolRegistryService.capabilities();
    }

    @Override
    public List<ToolRegistryItem> registry() {
        return designToolMapper.selectList(new LambdaQueryWrapper<DesignTool>()
                        .orderByAsc(DesignTool::getCategory)
                        .orderByAsc(DesignTool::getToolCode))
                .stream()
                .map(tool -> ToolRegistryItem.builder()
                        .toolCode(tool.getToolCode())
                        .name(tool.getName())
                        .category(tool.getCategory())
                        .realness(tool.getRealness())
                        .enabled(tinyIntToBool(tool.getEnabled()))
                        .available(tinyIntToBool(tool.getAvailable()))
                        .probeMsg(tool.getProbeMsg())
                        .agentExposed(tinyIntToBool(tool.getAgentExposed()))
                        .agentToolName(tool.getAgentToolName())
                        .build())
                .toList();
    }

    private DesignTool require(Long id) {
        DesignTool tool = id == null ? null : designToolMapper.selectById(id);
        if (tool == null) {
            throw new BizException(ErrorCode.TOOL_NOT_FOUND, "工具不存在：" + id);
        }
        return tool;
    }

    /**
     * 要求已登录（不要求管理员）。
     *
     * <p>只加在<b>公开端点</b>用到的读方法上（{@code list} / {@code get}）。这是问题总账 H-27 的整改：
     * 在此之前这两个端点只靠网关验签，**同网络内绕过网关直连服务**（不带任何身份头）能直接读到
     * 工具注册表 —— 实测过直连 `http://platform:8085/api/design/capabilities` 返回 200。
     *
     * <p><b>{@code capabilities()} 与 {@code registry()} 刻意不加</b>：它们被
     * {@code InternalToolController} 以服务间调用方式复用，那条路径没有用户身份；
     * 公开端点 {@code GET /api/design/capabilities} 的登录校验因此放在 controller 层。
     */
    private static void requireLogin() {
        CurrentUser.requireUserId();
    }

    private static void requireAdmin() {
        CurrentUser.Identity identity = CurrentUser.get();
        String role = identity == null ? null : identity.role();
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "工具注册表为运维接口，仅管理员可操作");
        }
    }

    private ToolResponse toResponse(DesignTool tool) {
        return ToolResponse.builder()
                .id(tool.getId())
                .toolCode(tool.getToolCode())
                .name(tool.getName())
                .description(tool.getDescription())
                .category(tool.getCategory())
                .type(tool.getType())
                .provider(tool.getProvider())
                .endpoint(tool.getEndpoint())
                .httpMethod(tool.getHttpMethod())
                .inputSchema(jsonCodec.readMap(tool.getInputSchema()))
                .outputSchema(jsonCodec.readMap(tool.getOutputSchema()))
                .asyncExecution(tinyIntToBool(tool.getAsyncExecution()))
                .timeoutMs(tool.getTimeoutMs())
                .requiresEngine(tool.getRequiresEngine())
                .capabilityKey(tool.getCapabilityKey())
                .realness(tool.getRealness())
                .enabled(tinyIntToBool(tool.getEnabled()))
                .available(tinyIntToBool(tool.getAvailable()))
                .probeMsg(tool.getProbeMsg())
                .lastProbeAt(tool.getLastProbeAt())
                .agentExposed(tinyIntToBool(tool.getAgentExposed()))
                .agentToolName(tool.getAgentToolName())
                .version(tool.getVersion())
                .build();
    }

    private static Integer boolToTinyInt(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private static Boolean tinyIntToBool(Integer value) {
        return value != null && value == 1;
    }

    private static <T> T defaultIfNull(T value, T fallback) {
        return value == null ? fallback : value;
    }
}