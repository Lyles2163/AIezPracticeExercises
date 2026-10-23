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

    /** 启用中的科目，按 sort_order 升序 */
    @Select("SELECT id, name, owner_id, subject_key, icon, description, sort_order, status "
            + "FROM qb_subject WHERE status = 1 ORDER BY sort_order ASC, id ASC")
    List<QbSubject> selectEnabledSubjects();

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
