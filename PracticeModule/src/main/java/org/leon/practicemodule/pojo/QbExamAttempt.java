package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 题库-用户试卷作答表（qb_exam_attempt）。一次开考一行，状态机是
 * 1 草稿 / 2 已提交 / 3 超时自动提交（spec 4.4）。
 *
 * <p>段 4 没有任何代码写这张表——只有两个只读接口在聚合它（卡片上的已提交次数与进度）。
 * 于是两条不变式现在只是约定，段 5 落地时要由它自己守住：
 * <ul>
 *   <li>{@code answeredJson} 是草稿期间唯一的答案真相，交卷那一次事务里展开成
 *       qb_user_answer_record 的行并置 NULL——map 与行不能同时是真相。</li>
 *   <li>{@code score}/{@code correctCount}/{@code wrongCount}/{@code unansweredCount}
 *       是交卷时的快照，之后永不重算，所以读成绩单不需要聚合。</li>
 * </ul>
 *
 * <p>{@code startedAt}/{@code deadlineAt} 是 NOT NULL 且没有默认值，实测
 * {@code sql_mode=STRICT_TRANS_TABLES} 下漏列会被 MySQL 以 1364 拒绝，
 * 所以插入必须显式给这两个值。
 */
@Data
@TableName("qb_exam_attempt")
public class QbExamAttempt implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 逻辑关联 users_dev.id，无外键 */
    @TableField("user_id")
    private Long userId;

    /** 逻辑关联 qb_paper.id，无外键 */
    @TableField("paper_id")
    private Long paperId;

    /** 冗余自 qb_paper，让错题本与统计能复用同一批索引 */
    @TableField("subject_id")
    private Long subjectId;

    /** 第几次作答，从 1 递增；与 (user_id, paper_id) 组成唯一键 */
    @TableField("seq_no")
    private Integer seqNo;

    @TableField("status")
    private Integer status;

    @TableField("answered_json")
    private String answeredJson;

    @TableField("started_at")
    private Date startedAt;

    @TableField("deadline_at")
    private Date deadlineAt;

    @TableField("submit_at")
    private Date submitAt;

    @TableField("duration_used_ms")
    private Integer durationUsedMs;

    @TableField("score")
    private BigDecimal score;

    @TableField("correct_count")
    private Integer correctCount;

    @TableField("wrong_count")
    private Integer wrongCount;

    @TableField("unanswered_count")
    private Integer unansweredCount;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
