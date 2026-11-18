package com.jxgc.meattrace.common;

/**
 * 分页入参，口径见 docs/tech/03-接口文档.md 1.2：{@code ?page=1&size=20&sort=createdAt,desc}。
 *
 * <p>{@code size} 上限 100，超出按 100 处理；传 0 或负数按 1 处理。
 */
public class PageQuery {

    private static final int MAX_SIZE = 100;

    private int page = 1;
    private int size = 20;
    private String sort;

    public int getPage() {
        return Math.max(page, 1);
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    /** offset，供 MyBatis-Plus {@code Page} 使用。 */
    public long offset() {
        return (long) (getPage() - 1) * getSize();
    }
}
