package org.leon.practicemodule.mapper;

/**
 * 一批试卷各自的"已提交次数"（只数 status IN (2,3)，段 4 裁定三）。
 * 没有提交过的卷不会出现在这批行里，调用方按缺省 0 处理。
 */
public record AttemptSubmitCountRow(Long paperId, long submitCount) {
}
