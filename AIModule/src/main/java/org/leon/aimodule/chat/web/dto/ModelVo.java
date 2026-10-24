package org.leon.aimodule.chat.web.dto;

/** 形状对齐前端 PromptBarModel = {key, name, tag?}，supportsThinking 供思考线开关。 */
public record ModelVo(String key, String name, String tag, boolean supportsThinking) {
}
