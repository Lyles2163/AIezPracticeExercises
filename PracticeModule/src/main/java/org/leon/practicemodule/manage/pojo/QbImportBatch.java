package org.leon.practicemodule.manage.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("qb_import_batch")
public class QbImportBatch implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("owner_id")
    private Long ownerId;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("paper_id")
    private Long paperId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("total_parsed")
    private Integer totalParsed;

    @TableField("imported_count")
    private Integer importedCount;

    @TableField("skipped_count")
    private Integer skippedCount;

    @TableField("warnings_json")
    private String warningsJson;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private Date createdAt;
}
