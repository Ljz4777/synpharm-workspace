package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * target_structure · 靶点结构。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("target_structure")
public class TargetStructure {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** bigint，非空 */
    private Long targetId;

    /** 4 位 PDB ID；AlphaFold / 用户上传时为空 */
    private String pdbId;

    /** 所选链 */
    private String chainId;

    /** PDB/ALPHAFOLD/USER_UPLOAD */
    private String source;

    /** 来源内稳定标识：PDB=1M17:A / AF=P00533:v4 / UPLOAD=sha256前16位 */
    private String sourceKey;

    /** X_RAY/CRYO_EM/NMR/PREDICTED */
    private String experimentMethod;

    /** 分辨率 Å；预测模型为 NULL */
    private BigDecimal resolution;

    /** 落盘的 mmCIF/PDB 路径（对象存储上线后由 file_asset_id 取代） */
    private String filePath;

    /** 结构文件资产（file_asset.id） */
    private Long fileAssetId;

    /** 共晶配体数 */
    private Integer ligandCount;

    /** 已检测口袋数 */
    private Integer pocketCount;

    /** 1=项目默认使用的结构 */
    private Integer isPreferred;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
