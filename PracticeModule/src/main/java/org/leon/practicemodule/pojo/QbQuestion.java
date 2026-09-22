package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 题库-题目表
 * @TableName qb_question
 */
@TableName(value ="qb_question")
@Data
public class QbQuestion implements Serializable {
    /**
     * 题目ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 科目ID
     */
    @TableField(value = "subject_id")
    private Long subject_id;

    /**
     * 所属用户ID，1表示公共题库，逻辑关联 users_dev.id
     */
    @TableField(value = "owner_id")
    private Long owner_id;

    /**
     * 题型：1单选 2多选 3判断 4填空 5简答
     */
    @TableField(value = "type")
    private Integer type;

    /**
     * 题干
     */
    @TableField(value = "title")
    private String title;

    /**
     * 答案JSON
     */
    @TableField(value = "answer_json")
    private Object answer_json;

    /**
     * 答案解析
     */
    @TableField(value = "analysis")
    private String analysis;

    /**
     * 难度：1简单 2中等 3困难
     */
    @TableField(value = "difficulty")
    private Integer difficulty;

    /**
     * 标签数组，如["重点","易错"]
     */
    @TableField(value = "tags_json")
    private Object tags_json;

    /**
     * 状态：1启用，0禁用
     */
    @TableField(value = "status")
    private Integer status;

    /**
     * 题目排序
     */
    @TableField(value = "sort_order")
    private Integer sort_order;

    /**
     * 创建时间
     */
    @TableField(value = "created_at")
    private Date created_at;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at")
    private Date updated_at;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        QbQuestion other = (QbQuestion) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getSubject_id() == null ? other.getSubject_id() == null : this.getSubject_id().equals(other.getSubject_id()))
            && (this.getOwner_id() == null ? other.getOwner_id() == null : this.getOwner_id().equals(other.getOwner_id()))
            && (this.getType() == null ? other.getType() == null : this.getType().equals(other.getType()))
            && (this.getTitle() == null ? other.getTitle() == null : this.getTitle().equals(other.getTitle()))
            && (this.getAnswer_json() == null ? other.getAnswer_json() == null : this.getAnswer_json().equals(other.getAnswer_json()))
            && (this.getAnalysis() == null ? other.getAnalysis() == null : this.getAnalysis().equals(other.getAnalysis()))
            && (this.getDifficulty() == null ? other.getDifficulty() == null : this.getDifficulty().equals(other.getDifficulty()))
            && (this.getTags_json() == null ? other.getTags_json() == null : this.getTags_json().equals(other.getTags_json()))
            && (this.getStatus() == null ? other.getStatus() == null : this.getStatus().equals(other.getStatus()))
            && (this.getSort_order() == null ? other.getSort_order() == null : this.getSort_order().equals(other.getSort_order()))
            && (this.getCreated_at() == null ? other.getCreated_at() == null : this.getCreated_at().equals(other.getCreated_at()))
            && (this.getUpdated_at() == null ? other.getUpdated_at() == null : this.getUpdated_at().equals(other.getUpdated_at()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getSubject_id() == null) ? 0 : getSubject_id().hashCode());
        result = prime * result + ((getOwner_id() == null) ? 0 : getOwner_id().hashCode());
        result = prime * result + ((getType() == null) ? 0 : getType().hashCode());
        result = prime * result + ((getTitle() == null) ? 0 : getTitle().hashCode());
        result = prime * result + ((getAnswer_json() == null) ? 0 : getAnswer_json().hashCode());
        result = prime * result + ((getAnalysis() == null) ? 0 : getAnalysis().hashCode());
        result = prime * result + ((getDifficulty() == null) ? 0 : getDifficulty().hashCode());
        result = prime * result + ((getTags_json() == null) ? 0 : getTags_json().hashCode());
        result = prime * result + ((getStatus() == null) ? 0 : getStatus().hashCode());
        result = prime * result + ((getSort_order() == null) ? 0 : getSort_order().hashCode());
        result = prime * result + ((getCreated_at() == null) ? 0 : getCreated_at().hashCode());
        result = prime * result + ((getUpdated_at() == null) ? 0 : getUpdated_at().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", id=").append(id);
        sb.append(", subject_id=").append(subject_id);
        sb.append(", owner_id=").append(owner_id);
        sb.append(", type=").append(type);
        sb.append(", title=").append(title);
        sb.append(", answer_json=").append(answer_json);
        sb.append(", analysis=").append(analysis);
        sb.append(", difficulty=").append(difficulty);
        sb.append(", tags_json=").append(tags_json);
        sb.append(", status=").append(status);
        sb.append(", sort_order=").append(sort_order);
        sb.append(", created_at=").append(created_at);
        sb.append(", updated_at=").append(updated_at);
        sb.append(", serialVersionUID=").append(serialVersionUID);
        sb.append("]");
        return sb.toString();
    }
}