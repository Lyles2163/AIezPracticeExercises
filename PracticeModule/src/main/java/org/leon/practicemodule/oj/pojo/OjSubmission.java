package org.leon.practicemodule.oj.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 一次提交 + 判题结果。status 是字符串枚举：
 * PENDING 排队 / RUNNING 判题中 / AC WA TLE MLE CE RE SE 终态。
 */
@Data
@TableName("oj_submission")
public class OjSubmission implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("problem_id")
    private Long problemId;

    /** java / python3 / cpp */
    @TableField("language")
    private String language;

    @TableField("source_code")
    private String sourceCode;

    @TableField("status")
    private String status;

    /** 通过用例数 */
    @TableField("score")
    private Integer score;

    /** 用例总数 */
    @TableField("total")
    private Integer total;

    @TableField("time_used_ms")
    private Integer timeUsedMs;

    @TableField("memory_used_kb")
    private Integer memoryUsedKb;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
