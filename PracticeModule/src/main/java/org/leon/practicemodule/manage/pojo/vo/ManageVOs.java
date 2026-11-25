package org.leon.practicemodule.manage.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 个人题库管理页响应 VO 集合。 */
public final class ManageVOs {

    private ManageVOs() {
    }

    @Data
    public static class MySubjectVO {
        private Long id;
        private String name;
        private String subjectKey;
        private String icon;
        private String description;
        private Integer status;
        private long questionCount;
        private long paperCount;
        private Long createdAt;
        private Long updatedAt;
    }

    @Data
    public static class ImportResultVO {
        private Long batchId;
        private Long subjectId;
        private String subjectName;
        private String fileName;
        private String fileType;
        private int totalParsed;
        private int importedCount;
        private int skippedCount;
        private String status;
        private List<String> warnings;
        private Long paperId;
        private String paperTitle;
    }

    @Data
    public static class ImportBatchVO {
        private Long id;
        private Long subjectId;
        private String subjectName;
        private Long paperId;
        private String paperTitle;
        private String fileName;
        private String fileType;
        private long fileSize;
        private int totalParsed;
        private int importedCount;
        private int skippedCount;
        private List<String> warnings;
        private String status;
        private Long createdAt;
    }

    @Data
    public static class MyQuestionOptionVO {
        private String optionKey;
        private String content;
        private boolean correct;
    }

    @Data
    public static class MyQuestionVO {
        private Long questionId;
        private Long subjectId;
        private String subjectName;
        private Integer type;
        private String typeName;
        private String title;
        private List<MyQuestionOptionVO> options;
        private String answerJson;
        private String analysis;
        private Integer difficulty;
        private List<String> tags;
        private Integer status;
        private Integer sortOrder;
        private Long createdAt;
        private Long updatedAt;
    }

    @Data
    public static class MyPaperVO {
        private Long paperId;
        private Long subjectId;
        private String subjectName;
        private String title;
        private Integer examYear;
        private Integer examMonth;
        private Integer setNo;
        private List<String> badges;
        private Integer durationMinutes;
        private BigDecimal totalScore;
        private Integer questionCount;
        private String description;
        private Integer status;
        private Long createdAt;
        private Long updatedAt;
    }
}
