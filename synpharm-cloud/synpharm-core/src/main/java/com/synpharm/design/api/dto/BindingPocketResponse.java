package com.synpharm.design.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结合口袋（契约 {@code BindingPocket}）。
 *
 * <p>库列是扁平的 {@code center_x/y/z}，DTO 也保持扁平 —— 契约如此，
 * 前端 `design.ts` 里的 {@code center:{x,y,z}} + {@code radius} 是 **mock 派生的形状**
 * （表里既没有 radius 列，也没有嵌套的 center），以契约为准，待前端迁移。
 *
 * <p>{@code residues} 由库里的 {@code residues_json} 解析而来（{@code [{"chain":"A","resno":745,...}]}）。
 * 表里是 JSON 文本而不是关联表，所以这里必须解码 —— 解码失败会抛错而不是静默给空数组，
 * 否则「口袋没有残基」和「这一列写坏了」就分不清了。
 *
 * <p>{@code selectedStructureId} 是数据库生成列（{@code uk_structure_selected} 用），
 * **不属于对外契约**，不在此暴露。
 *
 * @author SynPharm Team
 */
@Data
public class BindingPocketResponse {

    private Long id;

    private Long structureId;

    /** 结构内的口袋序号（按 score 降序） */
    private Integer pocketNo;

    /** P2RANK / FPOCKET / LIGAND_BASED / MANUAL */
    private String method;

    /** 方法原始打分 */
    private BigDecimal score;

    /** 可成药性 0-1 */
    private BigDecimal druggability;

    /** 空腔体积 Å³ */
    private BigDecimal volume;

    private BigDecimal centerX;

    private BigDecimal centerY;

    private BigDecimal centerZ;

    /** 口袋残基；来源是 {@code residues_json} */
    private List<Map<String, Object>> residues = new ArrayList<>();

    /** 是否为当前选定的设计口袋（同一结构最多一个） */
    private Boolean isSelected;

    /** 人工备注（如「已知 ATP 位点」） */
    private String note;
}
