package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 实体列名的反射口径，单测（QbEntityColumnTest）与真库集成测试（QbQuestionMapperDbIT）共用，
 * 避免两处各写一份导致口径漂移。
 *
 * <p>口径说明：
 * <ul>
 *   <li>列集合只取显式声明的非空列名：{@code @TableId("x")} 或 {@code @TableField("x")}（且 exist = true）。
 *       显式列名是本任务的约定，若有人写 {@code @TableField} 却不给列名，列集合会少一列而被断言抓住。</li>
 *   <li>未标注任何注解的字段也必须计入"未映射字段"：MyBatis-Plus 仍会按驼峰转下划线把它们拼进
 *       BaseMapper 生成的 SQL（mapUnderscoreToCamelCase 已开启），正是 owner_id 那类
 *       Unknown column 故障的来源。</li>
 *   <li>{@code @TableField(exist = false)} 也算未映射：本任务的实体不该有非列字段。</li>
 * </ul>
 */
public final class QbEntityColumns {

    private QbEntityColumns() {
    }

    /**
     * 本任务交付的七个实体，顺序与 brief 的真实列表一致。
     * 单测与真库 IT 共用这一份名单，免得两边覆盖的表数漂移。
     */
    public static List<Class<?>> qbEntities() {
        return List.of(QbSubject.class, QbQuestion.class, QbQuestionOption.class,
                QbUserAnswerRecord.class, QbUserWrong.class, QbUserFavorite.class,
                QbUserSubjectStat.class);
    }

    /**
     * @return 实体上 {@code @TableName} 声明的表名；未标注或为空串时返回 null。
     */
    public static String tableNameOf(Class<?> entity) {
        TableName tableName = entity.getAnnotation(TableName.class);
        if (tableName == null || tableName.value().isEmpty()) {
            return null;
        }
        return tableName.value();
    }

    /**
     * 实体显式声明的列名，按字段声明顺序返回，<b>保留重复</b>，便于调用方查重复列名。
     */
    public static List<String> columnNamesOf(Class<?> entity) {
        List<String> cols = new ArrayList<>();
        for (Field f : entity.getDeclaredFields()) {
            if (isNonColumnField(f)) {
                continue;
            }
            TableId id = f.getAnnotation(TableId.class);
            if (id != null && !id.value().isEmpty()) {
                cols.add(id.value());
                continue;
            }
            TableField tf = f.getAnnotation(TableField.class);
            if (tf != null && tf.exist() && !tf.value().isEmpty()) {
                cols.add(tf.value());
            }
        }
        return cols;
    }

    /**
     * 去重后的列集合，用于与期望列集/真实列集做集合比较。
     */
    public static Set<String> columnsOf(Class<?> entity) {
        return new LinkedHashSet<>(columnNamesOf(entity));
    }

    /**
     * 没有显式列映射的实例字段名：既没有 {@code @TableId}，也没有 {@code exist = true} 的
     * {@code @TableField}。这类字段仍会被 MyBatis-Plus 自动映射，因此必须为空。
     */
    public static List<String> unmappedFieldNames(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Field f : entity.getDeclaredFields()) {
            if (isNonColumnField(f)) {
                continue;
            }
            if (!hasExplicitColumn(f)) {
                names.add(f.getName());
            }
        }
        return names;
    }

    /**
     * 列表里出现两次以上的列名，用于把"重复标注同一列"这种错误显式报出来。
     */
    public static List<String> duplicatedNames(List<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> dup = new ArrayList<>();
        for (String name : names) {
            if (!seen.add(name) && !dup.contains(name)) {
                dup.add(name);
            }
        }
        return dup;
    }

    /** 静态字段（含 serialVersionUID）与非字段成员不参与列映射。 */
    private static boolean isNonColumnField(Field f) {
        return Modifier.isStatic(f.getModifiers()) || f.isSynthetic();
    }

    private static boolean hasExplicitColumn(Field f) {
        if (f.getAnnotation(TableId.class) != null) {
            return true;
        }
        TableField tf = f.getAnnotation(TableField.class);
        return tf != null && tf.exist();
    }
}
