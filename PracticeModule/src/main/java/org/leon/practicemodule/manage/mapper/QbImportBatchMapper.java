package org.leon.practicemodule.manage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.practicemodule.manage.pojo.QbImportBatch;

import java.util.List;

@Mapper
public interface QbImportBatchMapper extends BaseMapper<QbImportBatch> {

    @Select("<script>SELECT COUNT(*) FROM qb_import_batch WHERE owner_id = #{ownerId}"
            + "<if test='subjectId != null'> AND subject_id = #{subjectId}</if>"
            + "</script>")
    long countByOwner(@Param("ownerId") Long ownerId, @Param("subjectId") Long subjectId);

    @Select("<script>SELECT id, owner_id, subject_id, paper_id, file_name, file_type, file_size,"
            + " total_parsed, imported_count, skipped_count, warnings_json, status, created_at"
            + " FROM qb_import_batch WHERE owner_id = #{ownerId}"
            + "<if test='subjectId != null'> AND subject_id = #{subjectId}</if>"
            + " ORDER BY id DESC LIMIT #{offset}, #{pageSize}</script>")
    List<QbImportBatch> selectPageByOwner(@Param("ownerId") Long ownerId,
                                          @Param("subjectId") Long subjectId,
                                          @Param("offset") long offset,
                                          @Param("pageSize") long pageSize);

    @Update("UPDATE qb_import_batch SET paper_id = NULL WHERE paper_id = #{paperId} AND owner_id = #{ownerId}")
    int clearPaperRef(@Param("ownerId") Long ownerId, @Param("paperId") Long paperId);

    @Select("SELECT id FROM qb_import_batch WHERE owner_id = #{ownerId} AND subject_id = #{subjectId}")
    List<Long> selectIdsBySubject(@Param("ownerId") Long ownerId, @Param("subjectId") Long subjectId);

    @Delete("DELETE FROM qb_import_batch WHERE owner_id = #{ownerId} AND subject_id = #{subjectId}")
    int deleteBySubject(@Param("ownerId") Long ownerId, @Param("subjectId") Long subjectId);
}
