package com.synpharm.platform.support;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.synpharm.platform.domain.model.DesignTool;
import com.synpharm.platform.infrastructure.client.CapabilityProbeClient;
import com.synpharm.platform.infrastructure.client.EngineCapabilities;
import com.synpharm.platform.infrastructure.persistence.DesignToolMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 能力探测回写测试（契约 03 §6.1 的判定规则表）。
 *
 * <p>重点在「成功时必须把 {@code probe_msg} 显式置回 null」：{@code updateById} 会跳过 null 字段，
 * 若用它写回，界面会一直留着上一次的失败原因（明明可用了还写着引擎未部署）—— 这个坑本项目已踩过两次。
 * 因此这里断言的是 **SQL 参数表里确实出现了一个 null**，而不是只看 available 的值。
 *
 * @author SynPharm Team
 */
class ToolRegistryServiceTest {

    private DesignToolMapper mapper;
    private CapabilityProbeClient probeClient;
    private ToolRegistryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DesignToolMapper.class);
        probeClient = mock(CapabilityProbeClient.class);
        service = new ToolRegistryService(mapper, probeClient, new CapabilityResolver());
    }

    private static DesignTool tool(String code, String capabilityKey, String requiresEngine, int enabled) {
        DesignTool tool = new DesignTool();
        tool.setId(1L);
        tool.setToolCode(code);
        tool.setCapabilityKey(capabilityKey);
        tool.setRequiresEngine(requiresEngine);
        tool.setEnabled(enabled);
        return tool;
    }

    private static EngineCapabilities caps(Map<String, Boolean> engines, Map<String, Boolean> models) {
        EngineCapabilities capabilities = new EngineCapabilities();
        capabilities.setEngines(engines);
        Map<String, EngineCapabilities.ModelCapability> modelMap = new HashMap<>();
        models.forEach((key, ready) -> {
            EngineCapabilities.ModelCapability model = new EngineCapabilities.ModelCapability();
            model.setReady(ready);
            modelMap.put(key, model);
        });
        capabilities.setModels(modelMap);
        return capabilities;
    }

    /** 捕获写库时真正绑定的 SQL 参数 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureWriteParams() {
        ArgumentCaptor<UpdateWrapper<DesignTool>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return captor.getValue().getParamNameValuePairs();
    }

    @Test
    @DisplayName("引擎能力为真且 enabled=1：available=1 且 probe_msg 显式置 null")
    void marksAvailableAndClearsProbeMsg() {
        when(mapper.selectList(any())).thenReturn(List.of(tool("mol_properties", "rdkit", "rdkit", 1)));
        when(probeClient.fetch()).thenReturn(caps(Map.of("rdkit", true), Map.of()));

        service.refreshAll();

        Map<String, Object> params = captureWriteParams();
        assertThat(params.values()).contains(1);
        assertThat(params.values()).anyMatch(value -> value == null);
    }

    @Test
    @DisplayName("引擎能力为假：available=0 且说明里带上引擎名")
    void marksUnavailableWithEngineName() {
        when(mapper.selectList(any())).thenReturn(List.of(tool("mol_docking", "vina", "vina", 1)));
        when(probeClient.fetch()).thenReturn(caps(Map.of("vina", false), Map.of()));

        service.refreshAll();

        Map<String, Object> params = captureWriteParams();
        assertThat(params.values()).contains(0);
        assertThat(params.values()).anyMatch(value -> String.valueOf(value).contains("vina")
                && String.valueOf(value).contains("未部署"));
    }

    @Test
    @DisplayName("探测请求失败：不可用 + 「引擎不可达」，且不透传 Reactor 内部描述")
    void marksUnreachableOnProbeFailure() {
        when(mapper.selectList(any()))
                .thenReturn(List.of(tool("predict_dti", "models.dti", "kan_modti", 1)));
        when(probeClient.fetch()).thenThrow(new IllegalStateException(
                "Did not observe any item or terminal signal within 5000ms in 'flatMap'"));

        service.refreshAll();

        Map<String, Object> params = captureWriteParams();
        assertThat(params.values()).contains(0);
        assertThat(params.values()).anyMatch(value -> String.valueOf(value).contains("引擎不可达"));
        assertThat(params.values()).noneMatch(value -> String.valueOf(value).contains("flatMap"));
    }

    @Test
    @DisplayName("enabled=0：不可用且说明已禁用（规则表未覆盖，由实现补齐）")
    void marksDisabledToolUnavailable() {
        when(mapper.selectList(any())).thenReturn(List.of(tool("mol_docking", "vina", "vina", 0)));
        when(probeClient.fetch()).thenReturn(caps(Map.of("vina", true), Map.of()));

        service.refreshAll();

        Map<String, Object> params = captureWriteParams();
        assertThat(params.values()).contains(0);
        assertThat(params.values()).anyMatch(value -> String.valueOf(value).contains("已禁用"));
    }

    @Test
    @DisplayName("键判不出：不可用且给出原因（不允许只置 0 不说为什么）")
    void marksUnresolvedWithReason() {
        when(mapper.selectList(any())).thenReturn(List.of(tool("report_generate", null, null, 1)));
        when(probeClient.fetch()).thenReturn(caps(Map.of(), Map.of()));

        service.refreshAll();

        Map<String, Object> params = captureWriteParams();
        assertThat(params.values()).contains(0);
        assertThat(params.values()).anyMatch(value -> String.valueOf(value).contains("未声明"));
    }

    @Test
    @DisplayName("库里没有任何工具时不写库")
    void skipsWhenNoTools() {
        when(mapper.selectList(any())).thenReturn(List.of());

        service.refreshAll();

        Mockito.verify(mapper, Mockito.never()).update(any(), any());
    }
}
