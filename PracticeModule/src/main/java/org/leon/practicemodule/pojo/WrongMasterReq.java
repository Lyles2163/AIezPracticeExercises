package org.leon.practicemodule.pojo;

import lombok.Data;

/** 错题置已掌握。只有 questionId：科目归属从错题行来，不给前端传错的机会 */
@Data
public class WrongMasterReq {

    private Long questionId;
}
