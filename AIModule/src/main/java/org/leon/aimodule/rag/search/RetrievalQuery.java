package org.leon.aimodule.rag.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class RetrievalQuery {

    @NotBlank(message = "不能为空")
    private String query;

    @Min(value = 1, message = "至少为 1")
    @Max(value = 20, message = "最多为 20")
    private Integer topK;

    /** null=不限学科；0=只看全学科通用；>0=本学科 OR 通用 */
    private Long subjectId;

    /** official / personal；缺省=两者都查 */
    private List<String> scope;
}
