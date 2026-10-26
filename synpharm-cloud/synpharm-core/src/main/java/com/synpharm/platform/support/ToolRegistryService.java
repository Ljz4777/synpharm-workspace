package com.synpharm.platform.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.platform.api.dto.CapabilitiesResponse;
import com.synpharm.platform.api.dto.CapabilityItem;
import com.synpharm.platform.domain.model.DesignTool;
import com.synpharm.platform.infrastructure.client.CapabilityProbeClient;
import com.synpharm.platform.infrastructure.client.EngineCapabilities;
import com.synpharm.platform.infrastructure.persistence.DesignToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力注册与探测（红线 R1/R2 的运行时，见 03 §6.1）。
 *
 * <pre>
 * {@code @Scheduled}(fixedDelay = 10min)  refreshAll()
 *   ├─ GET {fastapi}/v1/evaluate/capabilities      ← 引擎能力矩阵
 *   ├─ 合并 design_tool.capability_key → available
 *   └─ 写回 design_tool.available / probe_msg / last_probe_at（独立事务）
 * probe(tool)                          按需探测（POST /tools/{id}/probe）
 * </pre>
 *
 * <p><b>判定规则（单一权威，03 §6.1）</b>
 *
 * <table border="1">
 *   <caption>可用性判定</caption>
 *   <tr><th>条件</th><th>available</th><th>probeMsg</th></tr>
 *   <tr><td>矩阵中键为 true 且 enabled=1</td><td>1</td><td>null</td></tr>
 *   <tr><td>引擎能力为 false</td><td>0</td><td>引擎 X 未部署</td></tr>
 *   <tr><td>探测请求失败（超时/连不上）</td><td>0</td><td>引擎不可达：&lt;原因&gt;</td></tr>
 *   <tr><td>从未探测过</td><td>0（建表默认）</td><td>尚未探测</td></tr>
 * </table>
 *
 * <p>规则表未覆盖、由本实现补齐的两种情况（都保持「不可用 + 说明原因」的同一形状）：
 * {@code enabled=0} → 0 + 「工具已禁用」；键在矩阵中判不出 → 0 + {@link CapabilityResolver#explainUnresolved}。
 *
 * <p><b>写回必须用 {@code UpdateWrapper}</b>：{@code updateById} 会跳过 null 字段，
 * 而「探测成功」恰恰要把 {@code probe_msg} 置回 null —— 用 updateById 会留下上一次的失败原因，
 * 表现为「明明可用了，界面上还写着引擎未部署」。这个坑在本项目已踩过两次。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    private final DesignToolMapper designToolMapper;
    private final CapabilityProbeClient capabilityProbeClient;
    private final CapabilityResolver capabilityResolver;

    @Value("${platform.probe.interval-ms:600000}")
    private long probeIntervalMs;

    /** 最近一次成功的引擎能力矩阵（供 {@code GET /capabilities} 回传 engines/models） */
    private volatile EngineCapabilities lastCapabilities;

    /** 最近一次刷新成功的时间（从未成功则为 null） */
    private volatile LocalDateTime lastRefreshedAt;

    /** 串行化所有「读矩阵 + 写库」，避免定时刷新与按需探测互相覆盖 */
    private final Object refreshLock = new Object();

    /**
     * 定时全量刷新（默认 10 分钟）。
     *
     * <p>首次延迟刻意留出启动时间：应用刚起来时引擎可能尚未就绪，
     * 立刻探测会把一批「引擎不可达」写进库里，虽然下次会自愈，但会污染首屏。
     */
    @Scheduled(fixedDelayString = "${platform.probe.interval-ms:600000}",
               initialDelayString = "${platform.probe.initial-delay-ms:20000}")
    public void refreshAll() {
        localRefresh();
    }

    /**
     * 按需探测单个工具并回写（{@code POST /tools/{id}/probe}）。
     *
     * @return 刷新后的实体
     * @throws BizException {@link ErrorCode#TOOL_PROBE_FAILED} 探测请求本身失败（probeMsg 已回写）
     */
    public DesignTool probe(DesignTool tool) {
        synchronized (refreshLock) {
            EngineCapabilities capabilities;
            try {
                capabilities = capabilityProbeClient.fetch();
                lastCapabilities = capabilities;
                lastRefreshedAt = LocalDateTime.now();
            } catch (Exception e) {
                String reason = "引擎不可达：" + describeFailure(e);
                writeProbeResult(tool.getId(), false, reason);
                log.warn("按需探测失败 toolCode={} 原因={}", tool.getToolCode(), reason);
                throw new BizException(ErrorCode.TOOL_PROBE_FAILED, reason,
                        Map.of("probeMsg", reason));
            }
            applyOne(tool, capabilities);
            return designToolMapper.selectById(tool.getId());
        }
    }

    /** 当前能力矩阵（契约 `Capabilities`） */
    public CapabilitiesResponse capabilities() {
        List<DesignTool> tools = designToolMapper.selectList(
                new LambdaQueryWrapper<DesignTool>().orderByAsc(DesignTool::getToolCode));
        EngineCapabilities snapshot = lastCapabilities;
        return CapabilitiesResponse.builder()
                .probedAt(lastRefreshedAt)
                .tools(tools.stream()
                        .map(t -> new CapabilityItem(t.getToolCode(),
                                isAvailable(t), t.getProbeMsg(), t.getRealness()))
                        .toList())
                .engines(snapshot == null ? null : snapshot.getEngines())
                .models(snapshot == null ? null : flattenModels(snapshot))
                .build();
    }

    private void localRefresh() {
        List<DesignTool> tools = designToolMapper.selectList(new LambdaQueryWrapper<>());
        if (tools.isEmpty()) {
            return;
        }
        EngineCapabilities capabilities = null;
        String failure = null;
        try {
            capabilities = capabilityProbeClient.fetch();
        } catch (Exception e) {
            failure = "引擎不可达：" + describeFailure(e);
        }
        synchronized (refreshLock) {
            if (capabilities != null) {
                lastCapabilities = capabilities;
                lastRefreshedAt = LocalDateTime.now();
                log.info("能力矩阵刷新成功：engines={}, models={}, 工具 {} 个",
                        capabilities.getEngines() == null ? "-" : capabilities.getEngines().size(),
                        capabilities.getModels() == null ? "-" : capabilities.getModels().size(),
                        tools.size());
            } else {
                log.warn("能力矩阵刷新失败，{} 个工具统一标记为不可用：{}", tools.size(), failure);
            }
            for (DesignTool tool : tools) {
                if (capabilities == null) {
                    writeProbeResult(tool.getId(), false, failure);
                } else {
                    applyOne(tool, capabilities);
                }
            }
        }
    }

    /** 依据矩阵更新单个工具；判不出/禁用一律落成「不可用 + 原因」 */
    private void applyOne(DesignTool tool, EngineCapabilities capabilities) {
        if (tool.getEnabled() != null && tool.getEnabled() == 0) {
            writeProbeResult(tool.getId(), false, "工具已禁用（enabled=0）");
            return;
        }
        Boolean available = capabilityResolver.resolve(
                tool.getCapabilityKey(), tool.getRequiresEngine(), capabilities);
        if (available == null) {
            writeProbeResult(tool.getId(), false,
                    capabilityResolver.explainUnresolved(tool.getCapabilityKey(), tool.getRequiresEngine()));
            return;
        }
        if (Boolean.TRUE.equals(available)) {
            // 成功：probe_msg 必须清空，否则界面会留着上一次的失败原因
            writeProbeResult(tool.getId(), true, null);
        } else {
            String name = tool.getRequiresEngine() != null ? tool.getRequiresEngine() : tool.getCapabilityKey();
            writeProbeResult(tool.getId(), false, "引擎 " + name + " 未部署");
        }
    }

    private void writeProbeResult(Long toolId, boolean available, String probeMsg) {
        designToolMapper.update(null, new UpdateWrapper<DesignTool>()
                .eq("id", toolId)
                .set("available", available ? 1 : 0)
                .set("probe_msg", probeMsg)
                .set("last_probe_at", LocalDateTime.now()));
    }

    private static Boolean isAvailable(DesignTool tool) {
        return tool.getAvailable() != null && tool.getAvailable() == 1;
    }

    private static Map<String, Boolean> flattenModels(EngineCapabilities capabilities) {
        if (capabilities.getModels() == null) {
            return null;
        }
        Map<String, Boolean> flat = new LinkedHashMap<>();
        capabilities.getModels().forEach((key, value) ->
                flat.put(key, value == null ? Boolean.FALSE : value.getReady()));
        return flat;
    }

    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    /**
     * 把异常翻译成**给用户看的一句话**。
     *
     * <p>这串文案会经 {@code probe_msg} 直接渲染到界面上（红线 R2：能力不足必须可见且可理解），
     * 所以不能把 Reactor 的内部描述原样透传 —— 「Did not observe any item or terminal signal
     * within 5000ms in 'flatMap'」既长又无法指导操作，超时统一改写成能看懂的说法。
     */
    private static String describeFailure(Throwable e) {
        String raw = rootMessage(e);
        if (raw != null && raw.startsWith("Did not observe any item")) {
            return "探测超时（引擎未在配置的超时时间内响应）";
        }
        if (raw != null && raw.length() > 200) {
            return raw.substring(0, 200) + "…";
        }
        return raw;
    }
}