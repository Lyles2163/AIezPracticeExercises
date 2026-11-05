package org.leon.practicemodule.oj.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * OJ 编程题。语言不在这张表上：同一道题可被任意语言解答，语言跟着 oj_submission 走。
 */
@Data
@TableName("oj_problem")
public class OjProblem implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    @TableField("template_code")
    private String templateCode;

    /** 1简单 2中等 3困难 */
    @TableField("difficulty")
    private Integer difficulty;

    @TableField("time_limit_ms")
    private Integer timeLimitMs;

    @TableField("memory_limit_mb")
    private Integer memoryLimitMb;

    /** 1启用 0下架 */
    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
