package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体字段必须与真实列严格一致。
 * 本仓库已经因为 MyBatisX 生成的 QbQuestion 带着已被删掉的 owner_id 而整体不可用，
 * 所以用反射把"列名集合"钉死，而不是靠肉眼比对。
 */
class QbEntityColumnTest {

    private static Set<String> columnsOf(Class<?> entity) {
        Set<String> cols = new LinkedHashSet<>();
        for (Field f : entity.getDeclaredFields()) {
            TableId id = f.getAnnotation(TableId.class);
            if (id != null && !id.value().isEmpty()) {
                cols.add(id.value());
                continue;
            }
            TableField tf = f.getAnnotation(TableField.class);
            if (tf != null && !tf.value().isEmpty() && tf.exist()) {
                cols.add(tf.value());
            }
        }
        return cols;
    }

    private static void assertColumns(Class<?> entity, String... expected) {
        Set<String> actual = columnsOf(entity);
        assertEquals(Set.of(expected), actual, entity.getSimpleName() + " 的列集合不符");
        assertEquals(expected.length, actual.size(),
                entity.getSimpleName() + " 列数不符（有重复列名）: " + actual);
    }

    @Test
    @DisplayName("qb_question 没有 owner_id 列，实体也不得有")
    void qbQuestionColumns() {
        assertColumns(QbQuestion.class, "id", "subject_id", "type", "title", "answer_json",
                "analysis", "difficulty", "tags_json", "status", "sort_order",
                "created_at", "updated_at");
        assertFalse(columnsOf(QbQuestion.class).contains("owner_id"),
                "owner_id 已从 qb_question 删除，保留会让 BaseMapper 生成 Unknown column 的 SQL");
    }

    @Test
    @DisplayName("其余六张表的列集合钉死")
    void otherTables() {
        assertColumns(QbSubject.class, "id", "name", "owner_id", "subject_key", "icon",
                "description", "sort_order", "status", "created_at", "updated_at");
        assertColumns(QbQuestionOption.class, "id", "question_id", "option_key", "content",
                "is_correct", "sort_order", "created_at", "updated_at");
        assertColumns(QbUserAnswerRecord.class, "id", "user_id", "subject_id", "question_id",
                "user_answer_json", "is_correct", "duration_ms", "answer_mode", "created_at");
        assertColumns(QbUserWrong.class, "id", "user_id", "subject_id", "question_id",
                "wrong_count", "last_wrong_at", "status", "created_at", "updated_at");
        assertColumns(QbUserFavorite.class, "id", "user_id", "subject_id", "question_id", "created_at");
        assertColumns(QbUserSubjectStat.class, "id", "user_id", "subject_id", "total_count",
                "correct_count", "wrong_count", "last_answer_at", "created_at", "updated_at");
    }

    @Test
    @DisplayName("json 列一律用 String 承载，不引入 TypeHandler")
    void jsonColumnsAreString() {
        assertEquals(String.class, field("QbQuestion", "answerJson").getType());
        assertEquals(String.class, field("QbQuestion", "tagsJson").getType());
        assertEquals(String.class, field("QbUserAnswerRecord", "userAnswerJson").getType());
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
