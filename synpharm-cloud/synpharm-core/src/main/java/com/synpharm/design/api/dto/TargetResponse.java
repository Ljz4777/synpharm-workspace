package com.synpharm.design.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 靶点（契约 {@code Target}）。
 *
 * <p><b>本 DTO 比 openapi 的 schema 多 4 个字段</b>（{@code description} / {@code pathway} /
 * {@code relatedDiseases} / {@code knownDrugs}）：契约漏登记了它们，而
 * ① `14_target.sql` 建表时就按 `src/types/index.ts` 的 `Target` 接口对齐、这几列都在；
 * ② `测试数据准备清单.md` §2.A 明确把它们列为接口字段（{@code description} 还是**必填**）。
 * 少了它们，靶点库列表页没有描述可显示、筛选出来的行也没有"相关疾病/已知药物"可参考。
 * 加字段是**非破坏性**变更（只增不删），已同步写回 `openapi.yaml` 并在勘误里登记。
 *
 * <p><b>列表与详情返回同一个形状</b>（含 {@code structures} 及其 {@code pockets}），
 * 不做「列表只给壳、详情才给全」的裁剪：两者共用同一 schema，若语义不同，前端的类型
 * 与实际数据就对不上（同一个字段在列表里永远是空数组）。代价是列表多两次
 * {@code IN} 批量查询（见 {@code TargetAppServiceImpl}），与页大小无关。
 *
 * @author SynPharm Team
 */
@Data
public class TargetResponse {

    private Long id;

    private String uniprotId;

    private String name;

    private String chineseName;

    private String geneName;

    private String organism;

    /** 靶点类型，前端筛选项 */
    private String targetType;

    private String family;

    /** 疾病领域，前端筛选项 */
    private String diseaseArea;

    /** 功能描述（契约漏登记，见类注释） */
    private String description;

    /** 主要通路（契约漏登记，见类注释） */
    private String pathway;

    /** 相关疾病（契约漏登记，见类注释） */
    private String relatedDiseases;

    /** 已知药物 / 干预方式（契约漏登记，见类注释） */
    private String knownDrugs;

    /** 蛋白序列；DTI 预测必需，缺失则该靶点不能用于 DTI */
    private String sequence;

    private Integer sequenceLength;

    /** 已知 PDB ID，逗号分隔（契约是字符串，不是数组） */
    private String pdbIds;

    /** supported / beta / planned */
    private String status;

    /** 结构与口袋；两级都在这里展开 */
    private List<TargetStructureResponse> structures = new ArrayList<>();
}
