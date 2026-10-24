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
 * 题库-试卷表（qb_paper）。卷面结构整份存在 structure_json 里（parts→sections→questions），
 * 题目只按 questionId 引用 qb_question，不内嵌题干与选项（spec 4.2 第一条要点）——
 * 这样错题本、收藏本、科目统计与答题记录（全以 question_id 为键）对考试题同样成立。
 *
 * <p>两处口径值得写在这里，因为它们都会以"静默错"的形式回来：
 * <ul>
 *   <li>{@code structureJson} 用 String 承载、解析只在 PaperStructureParser 一处，不引
 *       JacksonTypeHandler：那要求 autoResultMap，而本模块没有自定义 XML 结果映射。</li>
 *   <li>{@code totalScore} 是 {@code decimal(6,2)}，比对一律 compareTo 而非 equals——
 *       14.0 与 14.00 的 scale 不同，equals 判不等，而它们是同一个钱数。</li>
 * </ul>
 *
 * <p>{@code examYear}/{@code examMonth} 故意不叫 year/month：两者与 MONTH() 等函数同形，
 * 而 MyBatis-Plus 生成的 SQL 不加反引号（spec 4.2 第五条要点）。
 */
@Data
@TableName("qb_paper")
public class QbPaper implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 逻辑关联 qb_subject.id，无外键 */
    @TableField("subject_id")
    private Long subjectId;

    /** 1 表示公共试卷；读侧的可见性口径是 owner_id IN (1, 当前用户) */
    @TableField("owner_id")
    private Long ownerId;

    @TableField("title")
    private String title;

    @TableField("exam_year")
    private Integer examYear;

    @TableField("exam_month")
    private Integer examMonth;

    /** 套次徽章，1=第一套；中文序数在 PaperCards 里拼，不在这里 */
    @TableField("set_no")
    private Integer setNo;

    @TableField("duration_minutes")
    private Integer durationMinutes;

    @TableField("total_score")
    private BigDecimal totalScore;

    /** 冗余自 structure_json，由四条校验的规则 1 保证与实际题数一致 */
    @TableField("question_count")
    private Integer questionCount;

    @TableField("structure_json")
    private String structureJson;

    @TableField("description")
    private String description;

    /** 1 启用 0 禁用：禁用卷列表不可见、详情报 40900，与下架题同一口径 */
    @TableField("status")
    private Integer status;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
