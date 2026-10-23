package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-用户科目统计表（本模块读写，uk_user_subject 一人一科一行）。
 * 只有科目维度的三个计数，没有 mode 维度，所以考试成绩不计入这里。
 */
@Data
@TableName("qb_user_subject_stat")
public class QbUserSubjectStat implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID，逻辑关联 users_dev.id，无外键 */
    @TableField("user_id")
    private Long userId;

    @TableField("subject_id")
    private Long subjectId;

    /** 累计作答数 */
    @TableField("total_count")
    private Integer totalCount;

    /** 累计答对数 */
    @TableField("correct_count")
    private Integer correctCount;

    /** 累计答错数 */
    @TableField("wrong_count")
    private Integer wrongCount;

    /** 最近作答时间，可为空 */
    @TableField("last_answer_at")
    private Date lastAnswerAt;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
