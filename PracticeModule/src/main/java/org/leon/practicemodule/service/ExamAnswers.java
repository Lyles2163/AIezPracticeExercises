package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code qb_exam_attempt.answered_json} 的唯一编解码出口。
 *
 * <p>形态是 {@code {"<questionId>": <整份 JSON 答案>}}：key 用字符串（JSON 对象没有数字 key），
 * value 直接放 JSON 文档本身而不是它的字符串形式，于是
 * {@code {"4":["光合作用"]}} 在 MySQL 里就是一个真数组，取出来不用二次解析。
 *
 * <p><b>value 的严格校验直接复用 {@link AnswerGrader#parseSingleValue}，不在这里再写一遍</b>
 * （roadmap 第 16 条记着这个坑发作过两次：Jackson 的 readTree 容忍尾随内容（{@code "A" "B"} 只取第一个）
 * 并把 1e999 读成 Infinity，而 MySQL 的 json 列两者都不认 → 落库时抛 DataIntegrityViolation
 * → 接口回 code=0（谎）。判分与存草稿是<b>两个时机</b>（草稿不判分），但"什么叫一份 MySQL 收得下的
 * JSON"是<b>一个事实</b>——两份校验一定有一份漏掉尾随检查，所以共用实现、分开调用点。
 *
 * <p>脏数据（decode 失败）报 40900 而不是"当空 map"：那种形态只有两种来源——有人手改过库，
 * 或者来了个我们不认识的写方。静默吞掉会让用户的整场作答凭空消失。
 */
public final class ExamAnswers {

    private static final Logger log = LoggerFactory.getLogger(ExamAnswers.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExamAnswers() {
    }

    /** NULL / 空串 = 从没存过草稿，回空 map（不是 null，调用方可以直接迭代） */
    public static Map<Long, String> decode(String answeredJson) {
        if (answeredJson == null || answeredJson.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(answeredJson);
        } catch (Exception e) {
            log.error("answered_json 解析失败，按数据异常处理：{}", firstLine(e.getMessage()));
            throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
        }
        if (root == null || !root.isObject()) {
            log.error("answered_json 不是 JSON 对象：{}", firstLine(answeredJson));
            throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
        }
        Map<Long, String> out = new LinkedHashMap<>();
        var it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            long questionId;
            try {
                questionId = Long.parseLong(e.getKey().trim());
            } catch (NumberFormatException ex) {
                log.error("answered_json 的 key 不是题号：{}", e.getKey());
                throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
            }
            // JsonNode.toString() 就是这段的 JSON 文本，与 writeValueAsString 同结果且不抛检查异常
            out.put(questionId, e.getValue().toString());
        }
        return Map.copyOf(out);
    }

    /**
     * 存草稿的唯一入口：key 必须是要得的题号，value 必须是整份单值 JSON。
     * 空 map 编成 {@code "{}"} 而不是 NULL——"存了一次草稿、一道没答"与"从没存过"
     * 在草稿语义上是同一件事，但让 answered_json 保持"写过就是对象"能让 decode 少一条分支。
     */
    public static String encode(Map<Long, String> answers) {
        ObjectNode node = MAPPER.createObjectNode();
        for (Map.Entry<Long, String> e : requireValid(answers)) {
            node.set(String.valueOf(e.getKey()), AnswerGrader.parseSingleValue(e.getValue()));
        }
        return node.toString();
    }

    /** 草稿的已答数 = key 数。与段 4 的 {@code PaperCards.answeredJsonKeyCount} 必须同口径 */
    public static int countOf(String answeredJson) {
        return decode(answeredJson).size();
    }

    /**
     * 只验不写：给"存草稿前先验请求体"这类时机用（`encode` 自己不需要它，
     * 它逐条 {@code parseSingleValue} 的时候已经验过了）。
     */
    public static void requireValuesValid(Map<Long, String> answers) {
        for (Map.Entry<Long, String> e : requireValid(answers)) {
            AnswerGrader.parseSingleValue(e.getValue());
        }
    }

    private static Iterable<Map.Entry<Long, String>> requireValid(Map<Long, String> answers) {
        if (answers == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案不能为空");
        }
        for (Map.Entry<Long, String> e : answers.entrySet()) {
            if (e.getKey() == null || e.getKey() <= 0L) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案的题号不合法");
            }
        }
        return answers.entrySet();
    }

    private static String firstLine(String msg) {
        if (msg == null) {
            return "";
        }
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }
}
