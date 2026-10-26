package com.synpharm.design.api.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识项层级节点（契约 {@code LibraryItemNode}）：在 {@link LibraryItemResponse} 上挂 {@code children}。
 *
 * @author SynPharm Team
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LibraryItemNodeResponse extends LibraryItemResponse {

    /** 子节点；叶子为空数组而不是 null，省得前端到处判空 */
    private List<LibraryItemNodeResponse> children = new ArrayList<>();
}
