package com.synpharm.platform.application.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.constant.InternalHeaders;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.InternalRequestFilter;
import com.synpharm.common.security.SynpharmInternalProperties;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.platform.api.dto.ToolRegistryItem;
import com.synpharm.platform.api.dto.ToolUpsertRequest;
import com.synpharm.platform.domain.model.DesignTool;
import com.synpharm.platform.domain.model.Realness;
import com.synpharm.platform.domain.model.ToolCategory;
import com.synpharm.platform.infrastructure.persistence.DesignToolMapper;
import com.synpharm.platform.support.ToolRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具注册表写路径测试。
 *
 * <p>锁死两条红线：
 * <ol>
 *   <li><b>可用性不可伪造</b>：{@code POST} 落的是「0 + 尚未探测」，{@code PATCH} 的 UPDATE 语句里
 *       不能出现 {@code available} / {@code probe_msg} / {@code last_probe_at} 三个列名 ——
 *       否则等于允许客户端直接声称「这个引擎可用」。</li>
 *   <li><b>写接口仅管理员</b>：{@code design_tool.endpoint} 会被 design 的命令注册表直接调用，
 *       开放给任意登录用户等于开了内网地址入口。</li>
 * </ol>
 *
 * <p>身份的注入走**真实的 `InternalRequestFilter`**（与生产同一条路径），而不是往
 * ThreadLocal 里硬塞 —— 后者会在过滤器语义变化时失去意义。
 *
 * @author SynPharm Team
 */
class ToolAppServiceImplTest {

    private static final String TOKEN = "test-internal-token";

    private DesignToolMapper mapper;
    private ToolAppServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(DesignToolMapper.class);
        ToolRegistryService registry = mock(ToolRegistryService.class);
        service = new ToolAppServiceImpl(mapper, registry, new JsonCodec(new ObjectMapper()));
    }

    /** 在「经网关 + 指定角色」的身份下执行动作 */
    private <T> T asRole(String role, Supplier<T> action) {
        SynpharmInternalProperties properties = new SynpharmInternalProperties();
        properties.setToken(TOKEN);
        InternalRequestFilter filter = new InternalRequestFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/design/tools");
        request.addHeader(InternalHeaders.INTERNAL_TOKEN, TOKEN);
        if (role != null) {
            request.addHeader(InternalHeaders.USER_ROLE, role);
            request.addHeader(InternalHeaders.USER_ID, "7");
        }
        AtomicReference<T> result = new AtomicReference<>();
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> result.set(action.get()));
        } catch (RuntimeException e) {
            // 业务异常要原样抛出，否则断言只看得到包装层，被测行为就失真了
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result.get();
    }

    private static ToolUpsertRequest validRequest() {
        ToolUpsertRequest request = new ToolUpsertRequest();
        request.setToolCode("mol_properties");
        request.setName("分子理化性质");
        request.setCategory(ToolCategory.EVALUATION);
        request.setRealness(Realness.REAL);
        request.setCapabilityKey("rdkit");
        return request;
    }

    @Test
    @DisplayName("注册时伪造 available / probeMsg 必须被忽略（红线 R1）")
    void ignoresForgedAvailabilityOnCreate() {
        ToolUpsertRequest request = validRequest();
        // 客户端硬塞：这两个字段根本不在 ToolUpsertRequest 里，Jackson 也会忽略；
        // 这里直接验证服务端落库值，确保没有别的路径能把它们带进来
        when(mapper.selectCount(any())).thenReturn(0L);

        asRole("ADMIN", () -> service.create(request));

        ArgumentCaptor<DesignTool> captor = ArgumentCaptor.forClass(DesignTool.class);
        verify(mapper).insert(captor.capture());
        DesignTool inserted = captor.getValue();
        assertThat(inserted.getAvailable()).isZero();
        assertThat(inserted.getProbeMsg()).isEqualTo("尚未探测");
        assertThat(inserted.getLastProbeAt()).isNull();
    }

    @Test
    @DisplayName("工具码重复：409 TOOL_CODE_DUPLICATED（先查再插，不依赖异常）")
    void rejectsDuplicatedToolCode() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> asRole("ADMIN", () -> service.create(validRequest())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOOL_CODE_DUPLICATED);
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("PATCH 的 UPDATE 语句不得出现能力事实三列")
    void patchNeverTouchesAbilityColumns() {
        when(mapper.selectById(1L)).thenReturn(existingTool());
        ToolUpsertRequest request = new ToolUpsertRequest();
        request.setEnabled(Boolean.FALSE);

        asRole("ADMIN", () -> service.patch(1L, request));

        ArgumentCaptor<Wrapper<DesignTool>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).contains("enabled");
        assertThat(sqlSet).doesNotContain("available");
        assertThat(sqlSet).doesNotContain("probe_msg");
        assertThat(sqlSet).doesNotContain("last_probe_at");
    }

    @Test
    @DisplayName("PATCH 不传的字段保持原值（不传 = 不变，与 design 域的「不传 = 清空」相反）")
    void patchKeepsOmittedFields() {
        when(mapper.selectById(1L)).thenReturn(existingTool());
        ToolUpsertRequest request = new ToolUpsertRequest();
        request.setEnabled(Boolean.FALSE);

        asRole("ADMIN", () -> service.patch(1L, request));

        ArgumentCaptor<Wrapper<DesignTool>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).doesNotContain("name");
        assertThat(sqlSet).doesNotContain("capability_key");
    }

    @Test
    @DisplayName("非管理员：403 FORBIDDEN，且不写库")
    void rejectsNonAdmin() {
        assertThatThrownBy(() -> asRole("USER", () -> service.create(validRequest())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("无身份（未配置内部令牌）：同样拒绝，不默认放行")
    void rejectsWhenAnonymous() {
        assertThatThrownBy(() -> service.create(validRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("工具不存在：404 TOOL_NOT_FOUND")
    void rejectsMissingTool() {
        when(mapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> asRole("ADMIN", () -> service.get(999L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOOL_NOT_FOUND);
    }

    @Test
    @DisplayName("注册表：布尔映射与 null 原因原样透出，且不需要登录身份（内网调用）")
    void registryMapsToolsWithoutUserIdentity() {
        DesignTool available = existingTool();
        available.setAgentExposed(1);
        available.setAgentToolName("mol_properties");
        // probeMsg 保持 null = 探测成功后被清空，界面据此不显示原因
        assertThat(available.getProbeMsg()).isNull();

        DesignTool disabled = new DesignTool();
        disabled.setToolCode("pocket_detect");
        disabled.setName("口袋检测");
        disabled.setCategory("PREDICTION");
        disabled.setRealness("REAL");
        disabled.setEnabled(0);
        disabled.setAvailable(0);
        disabled.setProbeMsg("引擎 p2rank 未部署");
        disabled.setAgentExposed(0);

        when(mapper.selectList(any())).thenReturn(List.of(available, disabled));

        // 不经 InternalRequestFilter：design 用内网令牌调用时没有用户角色，此处必须照样可用
        List<ToolRegistryItem> items = service.registry();

        assertThat(items).hasSize(2);
        ToolRegistryItem ok = items.get(0);
        assertThat(ok.getToolCode()).isEqualTo("mol_properties");
        assertThat(ok.getName()).isEqualTo("分子理化性质");
        assertThat(ok.getCategory()).isEqualTo("EVALUATION");
        assertThat(ok.getRealness()).isEqualTo("REAL");
        assertThat(ok.getEnabled()).isTrue();
        assertThat(ok.getAvailable()).isTrue();
        assertThat(ok.getProbeMsg()).isNull();
        assertThat(ok.getAgentExposed()).isTrue();
        assertThat(ok.getAgentToolName()).isEqualTo("mol_properties");

        ToolRegistryItem grey = items.get(1);
        assertThat(grey.getEnabled()).isFalse();
        assertThat(grey.getAvailable()).isFalse();
        assertThat(grey.getProbeMsg()).isEqualTo("引擎 p2rank 未部署");
        assertThat(grey.getAgentExposed()).isFalse();
        // 未暴露给智能体的工具 agentToolName 为 null，不该被编造成工具码
        assertThat(grey.getAgentToolName()).isNull();
    }

    @Test
    @DisplayName("无身份：list / get 401 且不查库（不靠网关兜底，服务自己 fail-closed）")
    void rejectsAnonymousReaders() {
        assertThatThrownBy(() -> service.list(null, null, 1, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(mapper, never()).selectPage(any(), any());
        verify(mapper, never()).selectById(any());
    }

    @Test
    @DisplayName("registry 不要求用户身份：design 用内网令牌调用时没有角色，必须照样可用")
    void registryDoesNotRequireUserIdentity() {
        when(mapper.selectList(any())).thenReturn(List.of(existingTool()));

        // 刻意不经过 InternalRequestFilter：与 design 的 /internal/tools/registry 调用同构
        assertThat(service.registry()).hasSize(1);
    }

    private static DesignTool existingTool() {
        DesignTool tool = new DesignTool();
        tool.setId(1L);
        tool.setToolCode("mol_properties");
        tool.setName("分子理化性质");
        tool.setCategory("EVALUATION");
        tool.setRealness("REAL");
        tool.setCapabilityKey("rdkit");
        tool.setEnabled(1);
        tool.setAvailable(1);
        return tool;
    }
}
