package org.leon.practicemodule.manage.pojo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 个人题库管理相关的请求体集合（手写 POJO，校验在服务层按 PracticeErrorCode 统一收口）。 */
public final class ManageRequests {

    private ManageRequests() {
    }

    @Data
    public static class SubjectSaveReq {
        private String name;
        private String icon;
        private String description;
        private Integer status;
    }

    @Data
    public static class OptionReq {
        private String optionKey;
        private String content;
    }

    @Data
    public static class QuestionSaveReq {
        private Long subjectId;
        private Integer type;
        private String title;
        private List<OptionReq> options;
        private String answerJson;
        private String analysis;
        private Integer difficulty;
        private List<String> tags;
        private Integer status;
    }

    @Data
    public static class BatchDeleteReq {
        private List<Long> ids;
    }

    @Data
    public static class PaperPackItemReq {
        private Long questionId;
        private BigDecimal score;
    }

    @Data
    public static class PaperPackReq {
        private Long subjectId;
        private String title;
        private Integer examYear;
        private Integer examMonth;
        private Integer setNo;
        private Integer durationMinutes;
        private String description;
        private BigDecimal defaultScore;
        private List<PaperPackItemReq> items;
    }

    @Data
    public static class PaperUpdateReq {
        private String title;
        private Integer examYear;
        private Integer examMonth;
        private Integer setNo;
        private Integer durationMinutes;
        private String description;
        private Integer status;
    }
}
