package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-科目表。
 * 与 qb_question 不同：本表保留 owner_id（DEFAULT 1），公共/个人题库的区分只落在这里。
 */
@Data
@TableName("qb_subject")
public class QbSubject implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    /** 所属用户ID，1表示公共题库，逻辑关联 users_dev.id */
    @TableField("owner_id")
    private Long ownerId;

    /** 科目标识，如 chinese / math */
    @TableField("subject_key")
    private String subjectKey;

    @TableField("icon")
    private String icon;

    @TableField("description")
    private String description;

    @TableField("sort_order")
    private Integer sortOrder;

    /** 状态：1启用 0禁用 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
