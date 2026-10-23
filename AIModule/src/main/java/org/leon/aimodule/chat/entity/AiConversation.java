package org.leon.aimodule.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_conversation")
public class AiConversation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String userName;
    private String title;
    private String modelKey;
    private String effort;
    private Long lastMessageId;
    private String lastPreview;
    private Long messageCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
