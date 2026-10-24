package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-用户答题记录表（逐题一行，本模块读写）。
 * user_answer_json 以 String 承载，判分在段 2 的 AnswerGrader 一侧。
 */
@Data
@TableName("qb_user_answer_record")
public class QbUserAnswerRecord implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID，逻辑关联 users_dev.id，无外键 */
    @TableField("user_id")
    private Long userId;

    @TableField("subject_id")
    private Long subjectId;

    /**
     * 考试作答ID，answer_mode=2 时非空，逻辑关联 qb_exam_attempt.id。
     * 段 4 只把列与字段对齐（列由本段的 DDL 加上），读写都在段 5 的交卷展开里。
     */
    @TableField("attempt_id")
    private Long attemptId;

    @TableField("question_id")
    private Long questionId;

    /** 用户提交的答案 JSON，列类型 json，NOT NULL */
    @TableField("user_answer_json")
    private String userAnswerJson;

    /** 是否答对：1是 0否，列 NOT NULL */
    @TableField("is_correct")
    private Integer isCorrect;

    /** 单题用时（毫秒），可为空 */
    @TableField("duration_ms")
    private Integer durationMs;

    /** 答题模式：1刷题 2考试 3智能练习 */
    @TableField("answer_mode")
    private Integer answerMode;

    @TableField("created_at")
    private Date createdAt;
}
