package org.leon.aimodule.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_message")
public class AiMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static final String ST_STREAMING = "streaming";
    public static final String ST_DONE = "done";
    public static final String ST_FAILED = "failed";
    public static final String ST_TRUNCATED = "truncated";
    public static final String ST_SUPERSEDED = "superseded";

    public static final String STOP_DONE = "done";
    public static final String STOP_STOPPED = "stopped";
    public static final String STOP_LENGTH = "length";
    public static final String STOP_UPSTREAM = "upstream";
    public static final String STOP_CLIENT_DISCONNECT = "client_disconnect";
    public static final String STOP_TIMEOUT = "timeout";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String role;
    private String content;
    private String status;
    private String stopReason;
    private String modelKey;
    private String effort;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long replyToMessageId;
    private String clientMsgId;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
