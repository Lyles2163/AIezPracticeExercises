package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-题目表。
 * 注意：本表没有 owner_id 列（公共/个人题库的区分只在 qb_subject 上），
 * PracticeModule 对本题库表只读。json 列以 String 承载，解析在 AnswerGrader 一侧。
 */
@Data
@TableName("qb_question")
public class QbQuestion implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("subject_id")
    private Long subjectId;

    /** 题型：1单选 2多选 3判断 4填空 5简答 */
    @TableField("type")
    private Integer type;

    @TableField("title")
    private String title;

    /** 答案 JSON：单选 "A"，多选 ["A","C"]，判断 true，填空 ["关键词"]，简答 文本 */
    @TableField("answer_json")
    private String answerJson;

    @TableField("analysis")
    private String analysis;

    /** 难度：1简单 2中等 3困难 */
    @TableField("difficulty")
    private Integer difficulty;

    @TableField("tags_json")
    private String tagsJson;

    /** 状态：1启用 0禁用 */
    @TableField("status")
    private Integer status;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
