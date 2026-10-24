package org.leon.practicemodule.mapper;

import java.util.Date;

/**
 * selectWrongPage 的投影行。lastWrongAt 保持 Date，epoch 毫秒换算在服务里做（spec 5.1），
 * title 保持原文，折叠与截断在 PracticeMasker.titleBrief 一处做。
 */
public record WrongPageRow(Long questionId, Integer wrongCount, Date lastWrongAt,
                           Integer status, Integer type, String title) {
}
