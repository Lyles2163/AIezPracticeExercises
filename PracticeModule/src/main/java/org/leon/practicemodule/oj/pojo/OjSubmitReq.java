package org.leon.practicemodule.oj.pojo;

import lombok.Data;

import java.io.Serializable;

/** 提交请求体。language 缺省按 java 处理。 */
@Data
public class OjSubmitReq implements Serializable {

    private Long problemId;

    /** java / python3 / cpp，为空时取 java */
    private String language;

    private String sourceCode;
}
