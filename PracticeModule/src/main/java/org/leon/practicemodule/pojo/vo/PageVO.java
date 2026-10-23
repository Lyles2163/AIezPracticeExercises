package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 统一分页壳。不外泄 MyBatis-Plus 的 IPage：它的 records/size/current 语义
 * 与前端约定的 pageNum/pageSize/total/pages 不一致，且字段名会随版本变。
 */
@Data
public class PageVO<T> {

    private long pageNum;
    private long pageSize;
    private long total;
    private long pages;
    private List<T> list;

    public static <T> PageVO<T> of(List<T> list, long total, long pageNum, long pageSize) {
        PageVO<T> vo = new PageVO<>();
        vo.setList(list == null ? List.of() : list);
        vo.setTotal(total);
        vo.setPageNum(pageNum);
        vo.setPageSize(pageSize);
        vo.setPages(pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize);
        return vo;
    }
}
