package com.synpharm.design.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 靶点结构（契约 {@code TargetStructure}）。
 *
 * <p>{@code filePath} **不对外**：它是本地落盘路径（「对象存储上线后由 file_asset_id 取代」），
 * 暴露给前端等于泄漏容器内目录结构，且前端也拿不到该路径对应的文件。
 * 需要文件的能力一律走 {@code fileAssetId}（asset 服务）。
 *
 * @author SynPharm Team
 */
@Data
public class TargetStructureResponse {

    private Long id;

    private Long targetId;

    /** 4 位 PDB ID；AlphaFold / 用户上传为空 */
    private String pdbId;

    private String chainId;

    /** PDB / ALPHAFOLD / USER_UPLOAD */
    private String source;

    /** 来源内稳定标识：PDB=1M17:A / AF=P00533:v4 / UPLOAD=sha256 前 16 位 */
    private String sourceKey;

    /** X_RAY / CRYO_EM / NMR / PREDICTED */
    private String experimentMethod;

    /** 分辨率 Å；预测模型为 null */
    private BigDecimal resolution;

    private Integer ligandCount;

    private Integer pocketCount;

    /** 是否该项目默认使用的结构 */
    private Boolean isPreferred;

    /** 结构文件资产（asset 服务的 file_asset.id） */
    private Long fileAssetId;

    private List<BindingPocketResponse> pockets = new ArrayList<>();
}
