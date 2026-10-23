package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-用户错题本（本模块读写）。
 * 答错累加 wrong_count 并把 status 置回 1；答对且原为未掌握才置 2。
 */
@Data
@TableName("qb_user_wrong")
public class QbUserWrong implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID，逻辑关联 users_dev.id，无外键 */
    @TableField("user_id")
    private Long userId;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("question_id")
    private Long questionId;

    @TableField("wrong_count")
    private Integer wrongCount;

    @TableField("last_wrong_at")
    private Date lastWrongAt;

    /** 掌握状态：1未掌握 2已掌握 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
