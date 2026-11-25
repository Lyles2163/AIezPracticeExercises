package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.pojo.QbSubject;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QbSubjectMapper extends BaseMapper<QbSubject> {

    /** 启用中的全部科目，按 sort_order 升序（保留供历史集成测试使用） */
    @Select("SELECT id, name, owner_id, subject_key, icon, description, sort_order, status "
            + "FROM qb_subject WHERE status = 1 ORDER BY sort_order ASC, id ASC")
    List<QbSubject> selectEnabledSubjects();

    /**
     * 对当前用户可见的启用科目：公共科目（owner_id=1）与当前用户自己的个人科目。
     * 公共科目排在前，个人科目排在后，各自按 (sort_order, id) 升序。
     */
    @Select("SELECT id, name, owner_id, subject_key, icon, description, sort_order, status "
            + "FROM qb_subject WHERE status = 1 AND (owner_id = 1 OR owner_id = #{userId}) "
            + "ORDER BY CASE WHEN owner_id = 1 THEN 0 ELSE 1 END ASC, sort_order ASC, id ASC")
    List<QbSubject> selectVisibleEnabledSubjects(@Param("userId") Long userId);

    /** 当前用户创建的全部个人科目（含停用，按更新时间/ID倒序供管理页使用；排除公共 owner_id=1） */
    @Select("SELECT id, name, owner_id, subject_key, icon, description, sort_order, status, created_at, updated_at "
            + "FROM qb_subject WHERE owner_id = #{userId} AND owner_id <> 1 "
            + "ORDER BY sort_order ASC, id DESC")
    List<QbSubject> selectPersonalSubjects(@Param("userId") Long userId);

    /** 一次查出多个科目的全部题目数（含停用），个人题库管理页使用 */
    @Select("<script>SELECT subject_id AS subjectId, COUNT(*) AS cnt FROM qb_question "
            + "WHERE subject_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY subject_id</script>")
    List<StatCountRow> countAllQuestionRows(@Param("ids") Collection<Long> ids);

    /** 一次查出多个科目的个人套卷数（含停用），个人题库管理页使用 */
    @Select("<script>SELECT subject_id AS subjectId, COUNT(*) AS cnt FROM qb_paper "
            + "WHERE owner_id = #{ownerId} AND subject_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY subject_id</script>")
    List<StatCountRow> countPersonalPaperRows(@Param("ownerId") Long ownerId,
                                              @Param("ids") Collection<Long> ids);

    /** 按 id 取科目名，AI 错题列表里要展示科目名。科目不存在时返回 null。 */
    @Select("SELECT name FROM qb_subject WHERE id = #{id}")
    String selectNameById(@Param("id") Long id);

    /**
     * 一次查出多个科目的启用题目数。
     * mapper 只吐投影行，转 Map 是服务的职责——聚合语义留在服务里，
     * 免得"返回 Map 还是 List"在每个查询上重新吵一次。
     */
    @Select("<script>SELECT subject_id AS subjectId, COUNT(*) AS cnt FROM qb_question "
            + "WHERE status = 1 AND subject_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY subject_id</script>")
    List<StatCountRow> countEnabledQuestionRows(@Param("ids") Collection<Long> ids);
}
