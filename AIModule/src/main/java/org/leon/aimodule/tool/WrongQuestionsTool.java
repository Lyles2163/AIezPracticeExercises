package org.leon.aimodule.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.tool.PracticeFeign.PracticeFeign;
import org.leon.aimodule.tool.dto.WrongQuestionDTO;
import org.leon.commonjwt.pojo.Result;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具：查询当前用户的未掌握错题。
 *
 * 模型在用户问"我有哪些错题"、"帮我看看我错了哪些题"、"复习错题"这类意图时应该调用它。
 * 参数只有可选的 subjectId（科目 id）和 limit（返回条数）。
 */
@Slf4j
@Component
public class WrongQuestionsTool implements ChatTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PracticeFeign client;

    public WrongQuestionsTool(PracticeFeign client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "query_wrong_questions";
    }

    @Override
    public String description() {
        return "查询当前用户的错题本中尚未掌握的错题，包含题干、选项、用户上次作答、正确答案和解析。"
                + "当用户想查看自己的错题、复习错题、或询问自己做错了哪些题时调用此工具。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "subjectId": {
                      "type": "integer",
                      "description": "可选，按科目 id 过滤；不传则查询所有科目"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "可选，返回的错题数量，默认 10，最大 50"
                    }
                  }
                }
                """;
    }

    @Override
    public String execute(String argumentsJson, String userName) {
        Long subjectId = null;
        Integer limit = null;
        try {
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                JsonNode node = MAPPER.readTree(argumentsJson);
                if (node.hasNonNull("subjectId")) {
                    subjectId = node.get("subjectId").asLong();
                }
                if (node.hasNonNull("limit")) {
                    limit = node.get("limit").asInt();
                }
            }
        } catch (Exception e) {
            log.warn("[tool] query_wrong_questions 参数解析失败，按默认参数执行 args={} err={}",
                    argumentsJson, e.getMessage());
        }

        try {
            Result<List<WrongQuestionDTO>> result = client.list(userName, subjectId, limit);
            if (result == null || result.getCode() == null || result.getCode() != 1) {
                String msg = result == null ? "返回为空" : result.getMsg();
                return MAPPER.writeValueAsString(java.util.Map.of(
                        "ok", false, "message", "查询错题失败：" + msg));
            }
            List<WrongQuestionDTO> data = result.getData();
            if (data == null || data.isEmpty()) {
                return MAPPER.writeValueAsString(java.util.Map.of(
                        "ok", true, "message", "你当前没有未掌握的错题，继续保持！"));
            }
            return MAPPER.writeValueAsString(java.util.Map.of("ok", true, "questions", data));
        } catch (Exception e) {
            log.warn("[tool] query_wrong_questions 调用 PracticeModule 失败 user={} err={}",
                    userName, e.getMessage());
            return "{\"ok\":false,\"message\":\"查询错题失败，请稍后重试\"}";
        }
    }
}
