package com.jxgc.meattrace.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;
import java.util.function.Function;

/**
 * 分页出参，口径见 docs/tech/03-接口文档.md 1.2：{@code { page, size, total, records }}。
 */
public record PageResult<T>(long page, long size, long total, List<T> records) {

    public static <T> PageResult<T> of(IPage<T> source) {
        return new PageResult<>(source.getCurrent(), source.getSize(), source.getTotal(), source.getRecords());
    }

    /** 实体分页转 VO 分页。 */
    public static <E, V> PageResult<V> of(IPage<E> source, Function<E, V> mapper) {
        List<V> list = source.getRecords().stream().map(mapper).toList();
        return new PageResult<>(source.getCurrent(), source.getSize(), source.getTotal(), list);
    }

    public static <T> PageResult<T> of(PageQuery query, long total, List<T> records) {
        return new PageResult<>(query.getPage(), query.getSize(), total, records);
    }
}
