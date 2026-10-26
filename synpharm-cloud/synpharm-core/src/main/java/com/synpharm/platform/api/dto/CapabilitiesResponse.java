package com.synpharm.platform.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 当前能力矩阵（契约 `Capabilities`，前端能力状态灯）。
 *
 * @author SynPharm Team
 */
@Data
@Builder
public class CapabilitiesResponse {

    /** 本注册表最后一次刷新成功的时间；从未探测过为 null（此时 {@code tools[].available} 全为 false） */
    private LocalDateTime probedAt;

    private List<CapabilityItem> tools;

    /** 引擎能力：rdkit / crem / p2rank / vina / admet …；未探测到为 null */
    private Map<String, Boolean> engines;

    /** 模型能力：dti / ppi / ddi → 是否 ready；未探测到为 null */
    private Map<String, Boolean> models;
}