package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.pojo.QbPaper;

/**
 * 试卷可见性的四道门，{@code paper/page}、{@code paper/detail}（段 4）与 {@code exam/*}（段 5）共用。
 *
 * <p>抽出来的理由不是"复用代码"，是<b>防止两处漂</b>：段 4 把 40900 定成"试卷已下架"、
 * 40300 定成"无权访问该试卷"，如果段 5 的 start 再写一遍，将来加一条"只有 owner 能开考"
 * 之类的规则时一定会漏掉另一边，于是同一个资源在两个接口给出两个码。
 *
 * <p>{@code PUBLIC_OWNER = 1} 与列表 SQL 的 {@code owner_id IN (1, 当前用户)} 同口径
 * （spec 4.1 的 owner_id 注释）。显式判空挡的是"将来有人把列改成可空"，不是防 NPE——列本身 NOT NULL DEFAULT 1。
 */
public final class PaperLoad {

    /** 1 表示公共试卷 */
    public static final long PUBLIC_OWNER = 1L;

    private PaperLoad() {
    }

    /** 四道门按"参数 → 存在 → 上架 → 归属"的顺序过，先便宜的后贵的 */
    public static QbPaper loadVisible(QbPaperMapper mapper, Long userId, Long paperId) {
        if (paperId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbPaper paper = mapper.selectById(paperId);
        if (paper == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "试卷不存在");
        }
        if (paper.getStatus() == null || paper.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "试卷已下架");
        }
        if (paper.getOwnerId() != null && paper.getOwnerId() != PUBLIC_OWNER
                && !paper.getOwnerId().equals(userId)) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权访问该试卷");
        }
        return paper;
    }
}
