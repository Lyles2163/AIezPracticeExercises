package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-用户收藏表（本模块读写）。
 * uk_user_question(user_id, question_id) 保证一人一题只有一条。
 */
@Data
@TableName("qb_user_favorite")
public class QbUserFavorite implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID，逻辑关联 users_dev.id，无外键 */
    @TableField("user_id")
    private Long userId;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("question_id")
    private Long questionId;

    @TableField("created_at")
    private Date createdAt;
}
