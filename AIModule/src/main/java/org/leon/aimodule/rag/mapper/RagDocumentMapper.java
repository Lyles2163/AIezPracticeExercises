package org.leon.aimodule.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.rag.entity.RagDocument;

@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {

    /**
     * 插入文档行并回填自增 id。
     * <p>
     * 刻意不用 {@code BaseMapper.insert(entity)}：本项目 seata.enabled=true，数据源被
     * AT 模式代理，代理下自增主键回填并不可靠；而 docId 是后续插切片、写 ES 的前提，
     * 拿不到就没法继续。这里显式 useGeneratedKeys + keyProperty，绕开代理的回填差异。
     */
    @Insert("INSERT INTO rag_document (file_name, title, book, scope, owner_name, subject_id, "
            + "source_path, file_hash, file_size, mime_type, status, chunk_count, token_count) "
            + "VALUES (#{fileName}, #{title}, #{book}, #{scope}, #{ownerName}, #{subjectId}, "
            + "#{sourcePath}, #{fileHash}, #{fileSize}, #{mimeType}, #{status}, 0, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertReturningId(RagDocument doc);

    /**
     * 去重探测：同 (scope, owner_name, file_hash) 已有行就返回它。
     * <p>
     * 与唯一键 uk_scope_owner_hash 是同一组列。official 的 owner_name 恒为空串，
     * 所以重复上传同一份官方文档会命中这里；alice 的个人库与官方库 owner 不同，互不影响。
     */
    @Select("SELECT id, status FROM rag_document "
            + "WHERE scope = #{scope} AND owner_name = #{ownerName} AND file_hash = #{fileHash} LIMIT 1")
    RagDocument findByHash(@Param("scope") String scope, @Param("ownerName") String ownerName,
                           @Param("fileHash") String fileHash);

    /** 只改状态；处理中的阶段推进都走它，不碰其他列 */
    @Update("UPDATE rag_document SET status = #{status}, update_time = NOW(3) WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 终态成功：一次写完状态与统计，避免"状态已 indexed 但计数还是 0"的中间态被前端读到 */
    @Update("UPDATE rag_document SET status = 'indexed', chunk_count = #{chunkCount}, "
            + "token_count = #{tokenCount}, error_msg = NULL, update_time = NOW(3) WHERE id = #{id}")
    int markIndexed(@Param("id") Long id, @Param("chunkCount") int chunkCount,
                    @Param("tokenCount") int tokenCount);

    /** 终态失败。不清 chunk_count：已成功的切片不回滚，计数留着才有意义 */
    @Update("UPDATE rag_document SET status = 'failed', error_msg = #{errorMsg}, update_time = NOW(3) "
            + "WHERE id = #{id}")
    int markFailed(@Param("id") Long id, @Param("errorMsg") String errorMsg);
}
