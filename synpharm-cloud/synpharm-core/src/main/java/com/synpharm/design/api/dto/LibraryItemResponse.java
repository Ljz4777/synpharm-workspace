package com.synpharm.design.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 知识项响应（契约 {@code LibraryItem}）。
 *
 * <p>{@code meta} 是 {@code meta_json} 反序列化后的对象，其结构随 {@code category} 而定，
 * 所以用 Map 透传而不是为 6 个分类各建一个 DTO。
 *
 * @author SynPharm Team
 */
@Data
public class LibraryItemResponse {

    private Long id;

    private String category;

    private String code;

    private String name;

    private String nameEn;

    private Long parentId;

    private Integer levelNo;

    /** 物化路径，如 {@code /DISEASE/ONCOLOGY/NSCLC} */
    private String path;

    private Map<String, Object> meta;

    private Boolean enabled;

    private Boolean builtin;

    private Integer sortNo;

    /**
     * 备注。
     *
     * <p>契约 {@code LibraryItem} 原本没有这一列，但 {@code PATCH} 允许改 {@code remark}，
     * 不给读回就等于写入即丢失，故补齐（openapi 已同步）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String remark;
}
