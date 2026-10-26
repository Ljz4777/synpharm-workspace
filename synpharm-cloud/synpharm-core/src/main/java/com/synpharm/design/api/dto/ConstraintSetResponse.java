package com.synpharm.design.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 约束集响应（契约 {@code ConstraintSet}）。
 *
 * <p>契约同时给了 {@code id} 与 {@code setId}，语义上指向同一行，两者都填以兼容任意消费方。
 * {@code setNo} 是契约外的补充：它是用户可见的编号（{@code CS+yyMMddHHmmss+随机}），
 * 不给前端就没法在列表里辨认约束集。
 *
 * @author SynPharm Team
 */
@Data
public class ConstraintSetResponse {

    private Long id;

    private Long setId;

    /** 约束集编号，如 CS261014153045A7F3 */
    private String setNo;

    private String name;

    private String description;

    /** 顶层逻辑，固定 AND */
    private String logic;

    private String status;

    private Integer version;

    private Long parentId;

    private List<ConstraintRuleDto> rules = new ArrayList<>();
}
