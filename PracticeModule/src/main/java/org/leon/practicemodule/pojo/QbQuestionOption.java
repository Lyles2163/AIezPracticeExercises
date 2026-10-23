package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-题目选项表。
 * PracticeModule 只读；uk_question_option(question_id, option_key) 保证同题内选项标号唯一。
 */
@Data
@TableName("qb_question_option")
public class QbQuestionOption implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("question_id")
    private Long questionId;

    /** 选项标号：A/B/C/D */
    @TableField("option_key")
    private String optionKey;

    @TableField("content")
    private String content;

    /** 是否为正确选项：1是 0否。列为 tinyint，用 Integer 承载避免 null 拆箱 */
    @TableField("is_correct")
    private Integer isCorrect;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
