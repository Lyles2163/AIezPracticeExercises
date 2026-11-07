package org.leon.aimodule.chat.provider;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_user_model")
public class AiUserModel {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String userName;
    private String modelKey;
    private String displayName;
    /** CHAT / IMAGE / EMBEDDING / RERANK。null 视为 CHAT（升级前写入的行）。 */
    private String modelType;
    private Boolean supportsThinking;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
