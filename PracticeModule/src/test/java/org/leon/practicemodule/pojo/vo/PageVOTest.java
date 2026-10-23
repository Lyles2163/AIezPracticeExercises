package org.leon.practicemodule.pojo.vo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageVO 是阶段 1–5 所有分页接口的统一壳，先把它自己的算术钉住。
 * 重点不是"能拼出对象"，而是 pages 的向上取整、null 兜底、以及 pageSize<=0 这种
 * 传错就静默返回 pages=0 的行为（宁可在这里红，也不要在接口层静默少一页）。
 */
class PageVOTest {

    @Test
    @DisplayName("pages 向上取整：total=21, size=20 → 2 页")
    void pagesCeil() {
        PageVO<String> vo = PageVO.of(List.of("a"), 21, 1, 20);
        assertEquals(21, vo.getTotal());
        assertEquals(1, vo.getPageNum());
        assertEquals(20, vo.getPageSize());
        assertEquals(2, vo.getPages());
        assertEquals(1, vo.getList().size());
    }

    @Test
    @DisplayName("整除时不多出一页：total=40, size=20 → 2 页")
    void pagesExactDivision() {
        assertEquals(2, PageVO.of(List.of(), 40, 1, 20).getPages());
    }

    @Test
    @DisplayName("空结果集 total=0 → 0 页，list 为空列表而不是 null")
    void emptyResult() {
        PageVO<String> vo = PageVO.of(null, 0, 1, 20);
        assertNotNull(vo.getList(), "list 不能是 null，前端会直接 .map 它");
        assertTrue(vo.getList().isEmpty());
        assertEquals(0, vo.getPages());
    }

    @Test
    @DisplayName("pageSize<=0 时 pages 记 0 而不是抛除零异常")
    void nonPositivePageSize() {
        assertEquals(0, PageVO.of(List.of(), 10, 1, 0).getPages());
        assertEquals(0, PageVO.of(List.of(), 10, 1, -5).getPages());
    }
}