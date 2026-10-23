package org.leon.aimodule.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.chat.entity.AiConversation;

@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    @Update("UPDATE ai_conversation SET last_message_id = #{lastMessageId}, last_preview = #{lastPreview}, "
            + "message_count = message_count + #{delta}, update_time = NOW(3) WHERE id = #{id}")
    int touchOnNewMessage(@Param("id") Long id, @Param("lastMessageId") Long lastMessageId,
                          @Param("lastPreview") String lastPreview, @Param("delta") int delta);

    @Update("UPDATE ai_conversation SET title = #{title}, update_time = NOW(3) "
            + "WHERE id = #{id} AND title = '新对话'")
    int renameIfDefault(@Param("id") Long id, @Param("title") String title);
}
