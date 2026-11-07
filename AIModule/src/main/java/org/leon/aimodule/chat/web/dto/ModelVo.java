package org.leon.aimodule.chat.web.dto;

/**
 * 形状对齐前端 PromptBarModel = {key, name, tag?}，supportsThinking 供思考线开关。
 * 个人模型的 key 是 u:{providerId}:{modelId}，custom=true 且 tag 放供应商名，
 * 前端据此在模型下拉里分组展示。
 */
public record ModelVo(String key, String name, String tag, boolean supportsThinking,
                      /** 个人模型所属供应商显示名；内置模型为 null。 */
                      String providerName,
                      /** true 表示来自用户自建供应商。 */
                      boolean custom) {

    public ModelVo(String key, String name, String tag, boolean supportsThinking) {
        this(key, name, tag, supportsThinking, null, false);
    }
}
