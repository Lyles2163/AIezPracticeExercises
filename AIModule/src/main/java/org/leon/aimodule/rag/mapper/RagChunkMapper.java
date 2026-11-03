package org.leon.aimodule.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.rag.entity.RagChunk;

import java.util.List;

@Mapper
public interface RagChunkMapper extends BaseMapper<RagChunk> {

    /**
     * 批量插入切片。
     * <p>
     * <b>刻意不做主键回填</b>：ES 的 _id 需要 rag_chunk.id，但两条看似可行的捷径都不能用 ——
     * <ol>
     *   <li>「按插入顺序推算 id」：MySQL 8+ 默认 innodb_autoinc_lock_mode=2（interleaved），
     *       多行 INSERT 的自增值在并发下不保证连续，推算出来的 id 会安到别人的切片上。</li>
     *   <li>「靠 MyBatis useGeneratedKeys 回填」：批量 foreach + @Param 的 keyProperty 解析
     *       行为依赖 MyBatis 版本内部实现，不够稳。</li>
     * </ol>
     * 所以插完统一用 {@link #findByDocId} 按 seq 读回来，用 seq 配对正文与向量 —— 
     * seq 是应用自己编号的，与自增 id 怎么分配无关，天然稳定。
     * <p>
     * 单条 VALUES 由 foreach 展开成一次多值 INSERT，避免几百个 chunk 打几百次网络往返。
     */
    @Insert("""
            <script>
            INSERT INTO rag_chunk (doc_id, seq, content, token_count, page_no, chapter_path, index_status)
            VALUES
            <foreach collection="list" item="c" separator=",">
              (#{c.docId}, #{c.seq}, #{c.content}, #{c.tokenCount}, #{c.pageNo}, #{c.chapterPath}, #{c.indexStatus})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<RagChunk> chunks);

    /**
     * 批量标记 ES 写入结果。ids 为空时调用方不应调用本方法（foreach 会生成非法 SQL）。
     */
    @Update("""
            <script>
            UPDATE rag_chunk SET index_status = #{status}, error_msg = NULL, update_time = NOW(3)
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markIndexedBatch(@Param("ids") List<Long> ids, @Param("status") String status);

    /**
     * 重放清单：某文档下所有还没进 ES 的切片，按 seq 升序。
     * <p>
     * MySQL 写成功而 ES 写失败时，切片就停在 pending/embedded/failed，
     * 这条查询即待补清单 —— 数据不会丢，据此可以只重放失败的那部分。
     */
    @Select("SELECT id, doc_id, seq, content, token_count, page_no, chapter_path, index_status "
            + "FROM rag_chunk WHERE doc_id = #{docId} AND index_status <> 'indexed' ORDER BY seq")
    List<RagChunk> findNotIndexed(@Param("docId") Long docId);

    @Select("SELECT id, doc_id, seq, content, token_count, page_no, chapter_path, index_status "
            + "FROM rag_chunk WHERE doc_id = #{docId} ORDER BY seq")
    List<RagChunk> findByDocId(@Param("docId") Long docId);

    @Delete("DELETE FROM rag_chunk WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);

    @Select("SELECT COUNT(1) FROM rag_chunk WHERE doc_id = #{docId}")
    int countByDocId(@Param("docId") Long docId);

    /**
     * 强制重放：把某文档全部切片的 index_status 重置为 pending，
     * 供 reindex 接口重新走 embedding + 写 ES 的全流程。
     * 用于切片元数据（如 page_no）已在 MySQL 侧修正、需要把修正同步到 ES 的场景。
     */
    @Update("UPDATE rag_chunk SET index_status = 'pending', error_msg = NULL, update_time = NOW(3) "
            + "WHERE doc_id = #{docId}")
    int resetToPending(@Param("docId") Long docId);

    /** 把某文档全部切片的 page_no 置空。纯文本格式没有页概念，旧数据可能带着假的"第 1 页"。 */
    @Update("UPDATE rag_chunk SET page_no = NULL WHERE doc_id = #{docId}")
    int clearPageNo(@Param("docId") Long docId);
}
