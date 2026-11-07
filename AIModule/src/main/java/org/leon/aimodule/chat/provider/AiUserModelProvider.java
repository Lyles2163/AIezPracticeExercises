package org.leon.aimodule.chat.provider;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_user_model_provider")
public class AiUserModelProvider {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String userName;
    private String providerName;
    private String protocol;
    private String baseUrl;
    /** CSV：CHAT,IMAGE,RAG。null 视为 CHAT（升级前写入的行）。 */
    private String serviceTypes;
    private String apiKeyCiphertext;
    private String apiKeyNonce;
    private String apiKeyFingerprint;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
