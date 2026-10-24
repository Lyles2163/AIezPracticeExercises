package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

/**
 * 分页入参归一（spec 5.1：pageNum 从 1 起，pageSize 默认 20、上限 100）。
 *
 * 越界一律 40000，不静默夹到 100：静默夹会让前端以为"我要了 500 条"，
 * 实际只拿到 100 条，翻页游标就此错位且不报错——这类偏差最难查。
 * 值写成常量而不是 spec §8 里的 practice.page.* 配置：注入 bean 后单测要 new 服务反而更绕，
 * 而 20/100 的变更概率极低（段 3 计划里记了这处偏差）。
 */
public final class PracticePages {

    public static final long DEFAULT_SIZE = 20L;
    public static final long MAX_SIZE = 100L;
    /** 挡住 (pageNum-1)*pageSize 溢出成负数：pageSize 已 <=100，封顶后 offset <= 1e8 */
    public static final long MAX_PAGE_NUM = 1_000_000L;

    private PracticePages() {
    }

    public static PageParam of(Long pageNum, Long pageSize) {
        long num = pageNum == null ? 1L : pageNum;
        long size = pageSize == null ? DEFAULT_SIZE : pageSize;
        if (num < 1 || num > MAX_PAGE_NUM) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "页码不合法");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "每页条数只能是 1 到 100");
        }
        return new PageParam(num, size, (num - 1) * size);
    }

    public record PageParam(long pageNum, long pageSize, long offset) {
    }
}
