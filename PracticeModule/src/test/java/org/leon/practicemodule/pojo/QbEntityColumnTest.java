package org.leon.practicemodule.pojo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体字段必须与真实列严格一致。
 * 本仓库已经因为 MyBatisX 生成的 QbQuestion 带着已被删掉的 owner_id 而整体不可用，
 * 所以用反射把"列名集合"钉死，而不是靠肉眼比对。反射口径与真库 IT 共用 QbEntityColumns。
 *
 * <p>四道门：
 * <ol>
 *   <li>列集合与 brief 实测列一一对应，且实体自身不得出现重复列名；</li>
 *   <li>qb_question 不许有 owner_id；</li>
 *   <li>九个实体都显式声明非空 {@code @TableName}（漏了会退化成按类名推导表名）；</li>
 *   <li>每个实例字段都显式标注 {@code @TableId} 或 {@code exist = true} 的 {@code @TableField}。
 *       裸字段在本测试的列集合里"看不见"，但 MyBatis-Plus 仍按驼峰转下划线把它拼进
 *       BaseMapper 的 SELECT（mapUnderscoreToCamelCase 已开启），owner_id 那类
 *       Unknown column 故障就是这么来的。</li>
 * </ol>
 */
class QbEntityColumnTest {

    private static void assertColumns(Class<?> entity, String... expected) {
        // 列名按声明顺序保留重复，才能查出"两个字段标了同一列"这种错误。
        List<String> actual = QbEntityColumns.columnNamesOf(entity);
        List<String> duplicated = QbEntityColumns.duplicatedNames(actual);
        assertTrue(duplicated.isEmpty(),
                entity.getSimpleName() + " 有字段重复映射到同一列: " + duplicated + "，实际列: " + actual);
        Set<String> actualSet = new LinkedHashSet<>(actual);
        assertEquals(Set.of(expected), actualSet, entity.getSimpleName() + " 的列集合不符");
    }

    @Test
    @DisplayName("qb_question 没有 owner_id 列，实体也不得有")
    void qbQuestionColumns() {
        assertColumns(QbQuestion.class, "id", "subject_id", "type", "title", "answer_json",
                "analysis", "difficulty", "tags_json", "status", "sort_order",
                "created_at", "updated_at");
        assertFalse(QbEntityColumns.columnsOf(QbQuestion.class).contains("owner_id"),
                "owner_id 已从 qb_question 删除，保留会让 BaseMapper 生成 Unknown column 的 SQL");
    }

    @Test
    @DisplayName("qb_paper 的 16 列钉死：structure_json 用 String 承载，不引 TypeHandler")
    void qbPaperColumns() {
        assertColumns(QbPaper.class, "id", "subject_id", "owner_id", "title", "exam_year",
                "exam_month", "set_no", "duration_minutes", "total_score", "question_count",
                "structure_json", "description", "status", "sort_order", "created_at", "updated_at");
    }

    @Test
    @DisplayName("qb_exam_attempt 的 17 列钉死：草稿与三个快照计数都在")
    void qbExamAttemptColumns() {
        assertColumns(QbExamAttempt.class, "id", "user_id", "paper_id", "subject_id", "seq_no",
                "status", "answered_json", "started_at", "deadline_at", "submit_at",
                "duration_used_ms", "score", "correct_count", "wrong_count", "unanswered_count",
                "created_at", "updated_at");
    }

    @Test
    @DisplayName("其余表的列集合钉死")
    void otherTables() {
        assertColumns(QbSubject.class, "id", "name", "owner_id", "subject_key", "icon",
                "description", "sort_order", "status", "created_at", "updated_at");
        assertColumns(QbQuestionOption.class, "id", "question_id", "option_key", "content",
                "is_correct", "sort_order", "created_at", "updated_at");
        assertColumns(QbUserAnswerRecord.class, "id", "user_id", "subject_id", "attempt_id",
                "question_id", "user_answer_json", "is_correct", "duration_ms", "answer_mode",
                "created_at");
        assertColumns(QbUserWrong.class, "id", "user_id", "subject_id", "question_id",
                "wrong_count", "last_wrong_at", "status", "created_at", "updated_at");
        assertColumns(QbUserFavorite.class, "id", "user_id", "subject_id", "question_id", "created_at");
        assertColumns(QbUserSubjectStat.class, "id", "user_id", "subject_id", "total_count",
                "correct_count", "wrong_count", "last_answer_at", "created_at", "updated_at");
    }

    @Test
    @DisplayName("九个实体都显式声明非空 @TableName")
    void everyEntityDeclaresTableName() {
        for (Class<?> entity : QbEntityColumns.qbEntities()) {
            String table = QbEntityColumns.tableNameOf(entity);
            assertNotNull(table, entity.getSimpleName() + " 没有声明 @TableName，"
                    + "MyBatis-Plus 会按类名推导表名");
            assertFalse(table.isEmpty(), entity.getSimpleName() + " 的 @TableName 是空串");
        }
    }

    @Test
    @DisplayName("每个实例字段都得有显式列标注，不许留裸字段")
    void everyFieldIsAnnotated() {
        for (Class<?> entity : QbEntityColumns.qbEntities()) {
            List<String> bare = QbEntityColumns.unmappedFieldNames(entity);
            assertTrue(bare.isEmpty(), entity.getSimpleName() + " 的字段未标注 @TableId/@TableField，"
                    + "MyBatis-Plus 仍会把它们按驼峰转下划线拼进 SELECT: " + bare);
        }
    }

    @Test
    @DisplayName("json 列一律用 String 承载，不引入 TypeHandler")
    void jsonColumnsAreString() {
        assertEquals(String.class, field("QbQuestion", "answerJson").getType());
        assertEquals(String.class, field("QbQuestion", "tagsJson").getType());
        assertEquals(String.class, field("QbUserAnswerRecord", "userAnswerJson").getType());
        assertEquals(String.class, field("QbPaper", "structureJson").getType());
        assertEquals(String.class, field("QbExamAttempt", "answeredJson").getType());
    }

    private static Field field(String simpleName, String fieldName) {
        try {
            Class<?> c = Class.forName("org.leon.practicemodule.pojo." + simpleName);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            throw new AssertionError(simpleName + "." + fieldName + " 不存在", e);
        }
    }
}
