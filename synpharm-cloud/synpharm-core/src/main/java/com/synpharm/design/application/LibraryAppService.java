package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.LibraryItemNodeResponse;
import com.synpharm.design.api.dto.LibraryItemResponse;
import com.synpharm.design.api.dto.LibraryItemUpsertRequest;

import java.util.List;

/**
 * 知识库用例。
 *
 * <p>知识库是全平台共享的参考数据（疾病 / 靶点 / 分子形式 / 约束模板 / 目标 / 检测类型），
 * {@code design_library_item} 没有属主列，因此本组端点**不做归属校验**，但仍要求已登录
 * （网关已保证）。
 *
 * @author SynPharm Team
 */
public interface LibraryAppService {

    /**
     * 分页检索知识项。
     *
     * @param category 分类，必填
     * @param parentId 上级过滤，可空
     * @param keyword  匹配 code/name/nameEn，可空
     * @param enabled  启用状态过滤，可空
     */
    PageResult<LibraryItemResponse> list(String category, Long parentId, String keyword, Boolean enabled,
                                         int page, int pageSize);

    /**
     * 某分类的完整层级树（一次拉全，驱动下拉/级联）。
     *
     * @param category    分类，必填
     * @param enabledOnly 只返回启用项
     */
    List<LibraryItemNodeResponse> tree(String category, boolean enabledOnly);

    /** 详情；不存在返回 404 */
    LibraryItemResponse get(Long id);

    /** 新建；同分类 code 重复返回 409，父级非法返回 400 */
    LibraryItemResponse create(LibraryItemUpsertRequest request);

    /** 更新；内置项仅允许改 enabled/sortNo/remark */
    LibraryItemResponse update(Long id, LibraryItemUpsertRequest request);

    /** 逻辑删除；存在子节点时拒绝 */
    void delete(Long id);
}
