package org.leon.practicemodule.oj.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 一个测试用例。(problem_id, seq) 唯一。
 * is_sample=1 的用例会下发给用户当示例，is_sample=0 的隐藏用例绝不出接口。
 */
@Data
@TableName("oj_testcase")
public class OjTestcase implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("problem_id")
    private Long problemId;

    @TableField("seq")
    private Integer seq;

    @TableField("input_data")
    private String inputData;

    @TableField("expected_output")
    private String expectedOutput;

    @TableField("is_sample")
    private Integer isSample;

    @TableField("create_time")
    private Date createTime;
}
