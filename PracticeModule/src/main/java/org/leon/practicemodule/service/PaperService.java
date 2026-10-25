package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.PaperCardVO;
import org.leon.practicemodule.pojo.vo.PaperDetailVO;

/**
 * 套卷的读侧用例（段 4）。两个方法都不写库：qb_paper 由仓库外脚本灌入，
 * qb_exam_attempt 由段 5 的考试状态机写。
 */
public interface PaperService {

    /**
     * @param subjectId 必填，与段 3 的三个列表接口一致（"全部科目"页签要等产品定口径）
     * @param examYear  / examMonth / setNo 可选，任一为空即不参与过滤
     */
    PageVO<PaperCardVO> page(Long userId, Long subjectId, Integer examYear, Integer examMonth,
                             Integer setNo, Long pageNum, Long pageSize);

    /**
     * 卷面骨架（不含题干与选项）。
     *
     * @param userId 不是装饰参数：它决定三件事——越权判定（他人私有卷 40300）、
     *               进度三个字段的聚合、以及 {@code originalText} 能不能看见（段 4 裁定二）
     */
    PaperDetailVO detail(Long userId, Long paperId);
}
