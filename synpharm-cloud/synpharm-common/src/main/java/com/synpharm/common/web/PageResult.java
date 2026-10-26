package com.synpharm.common.web;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 偏移分页信封（规范 §1：{@code data.{list,total,page,pageSize,hasNext}}）。
 *
 * <p>为什么不让 MyBatis-Plus 的 {@code Page} 直接序列化：它会把 {@code records}/{@code size}
 * 等内部字段一并吐给前端，字段名与契约不符，且把 ORM 类型泄漏到 API。
 *
 * @param list     当前页数据
 * @param total    总条数
 * @param page     当前页（从 1 开始）
 * @param pageSize 每页条数
 * @param hasNext  是否还有下一页
 * @author SynPharm Team
 */
public record PageResult<T>(List<T> list, long total, long page, long pageSize, boolean hasNext) {

    /** 由 MyBatis-Plus 分页对象转换，同时对元素做 DTO 映射 */
    public static <S, T> PageResult<T> of(IPage<S> page, Function<S, T> mapper) {
        List<T> list = page.getRecords().stream().map(mapper).toList();
        return new PageResult<>(list, page.getTotal(), page.getCurrent(), page.getSize(),
                page.getCurrent() < page.getPages());
    }

    /**
     * 空页（不查库）。
     *
     * <p>用于「当前用户可见范围为空」这类可以短路的情况：与其拼一个恒假的 IN 条件，
     * 不如直接返回空页，省一次全表扫描。
     */
    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize, false);
    }
}
