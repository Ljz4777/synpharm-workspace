package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 知识项新建/更新请求（契约 {@code LibraryItemUpsert}）。
 *
 * <p>契约对 POST 与 PATCH 复用同一 schema，且 {@code category} / {@code code} / {@code name}
 * 均为必填 —— 即 PATCH 也要求带齐这三个字段（不是局部更新）。实现按契约执行。
 *
 * @author SynPharm Team
 */
@Data
public class LibraryItemUpsertRequest {

    @NotBlank(message = "category 不能为空")
    private String category;

    @NotBlank(message = "code 不能为空")
    @Size(max = 64, message = "code 最长 64 字符")
    private String code;

    @NotBlank(message = "name 不能为空")
    @Size(max = 200, message = "name 最长 200 字符")
    private String name;

    @Size(max = 200, message = "nameEn 最长 200 字符")
    private String nameEn;

    /** 上级项；为空表示根节点 */
    private Long parentId;

    /** 分类扩展属性，落库为 {@code meta_json} */
    private Map<String, Object> metaJson;

    private Integer sortNo;

    /** 默认 true（数据库默认 1） */
    private Boolean enabled;

    @Size(max = 500, message = "remark 最长 500 字符")
    private String remark;
}
