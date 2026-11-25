package org.leon.practicemodule.manage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.practicemodule.manage.pojo.QbImportBatchItem;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QbImportBatchItemMapper extends BaseMapper<QbImportBatchItem> {

    @Select("SELECT question_id FROM qb_import_batch_item WHERE batch_id = #{batchId} ORDER BY seq_no ASC, id ASC")
    List<Long> selectQuestionIdsByBatch(@Param("batchId") Long batchId);

    @Delete("DELETE FROM qb_import_batch_item WHERE batch_id = #{batchId}")
    int deleteByBatch(@Param("batchId") Long batchId);

    @Delete("<script>DELETE FROM qb_import_batch_item WHERE batch_id IN"
            + " <foreach collection='batchIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteByBatchIds(@Param("batchIds") Collection<Long> batchIds);

    @Delete("<script>DELETE FROM qb_import_batch_item WHERE question_id IN"
            + " <foreach collection='questionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteByQuestionIds(@Param("questionIds") Collection<Long> questionIds);
}
