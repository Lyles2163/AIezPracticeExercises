package org.leon.aimodule.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.chat.entity.AiMessage;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    @Update("UPDATE ai_message SET status = 'superseded', update_time = NOW(3) "
            + "WHERE reply_to_message_id = #{replyTo} AND role = 'assistant' AND status <> 'superseded'")
    int supersedePrevious(@Param("replyTo") Long replyTo);

    /**
     * 收尾写入只对流式中的行生效：AND status='streaming' 是"一条回答只 UPDATE 一次"的
     * 数据库层兜底。断连/stop 与 onComplete 谁先到谁赢，第二个 UPDATE 匹配 0 行，
     * 不会把已收尾的行改回另一种终态。
     */
    @Update("UPDATE ai_message SET content = #{content}, status = #{status}, stop_reason = #{stopReason}, "
            + "prompt_tokens = #{promptTokens}, completion_tokens = #{completionTokens}, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'streaming'")
    int finalizeAssistant(@Param("id") Long id, @Param("content") String content,
                          @Param("status") String status, @Param("stopReason") String stopReason,
                          @Param("promptTokens") Integer promptTokens,
                          @Param("completionTokens") Integer completionTokens);

    @Update("UPDATE ai_message SET content = #{content}, status = 'failed', stop_reason = 'upstream', "
            + "error_msg = #{errorMsg}, update_time = NOW(3) WHERE id = #{id} AND status = 'streaming'")
    int finalizeFailed(@Param("id") Long id, @Param("content") String content,
                       @Param("errorMsg") String errorMsg);

    @Update("UPDATE ai_message SET status = 'failed', error_msg = #{errorMsg}, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'streaming'")
    int markFailed(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    @Update("UPDATE ai_message SET status = 'failed', error_msg = #{errorMsg}, update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND role = 'assistant' AND status = 'streaming' "
            + "AND create_time < DATE_SUB(NOW(3), INTERVAL #{minutes} MINUTE)")
    int reapStaleStreaming(@Param("conversationId") Long conversationId, @Param("minutes") int minutes,
                           @Param("errorMsg") String errorMsg);

    @Select("SELECT COUNT(1) FROM ai_message WHERE conversation_id = #{conversationId} "
            + "AND role = 'assistant' AND status = 'streaming' "
            + "AND create_time >= DATE_SUB(NOW(3), INTERVAL #{minutes} MINUTE)")
    int countLiveStreaming(@Param("conversationId") Long conversationId, @Param("minutes") int minutes);
}
