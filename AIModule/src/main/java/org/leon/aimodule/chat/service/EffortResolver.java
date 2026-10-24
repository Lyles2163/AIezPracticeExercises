package org.leon.aimodule.chat.service;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.springframework.stereotype.Service;

/**
 * (modelKey, effort) → 上游调用参数。
 * effort 只改 temperature/maxTokens，绝不换模型：前端两个控件独立，
 * 换模型会让模型下拉变成误导。
 */
@Service
public class EffortResolver {

    private final ChatProperties props;

    public EffortResolver(ChatProperties props) {
        this.props = props;
    }

    public ModelParams resolve(String modelKey, String effort) {
        String mk = (modelKey == null || modelKey.isBlank()) ? props.getDefaultModel() : modelKey.trim();
        String ek = (effort == null || effort.isBlank()) ? props.getDefaultEffort() : effort.trim();

        ChatProperties.Model model = props.model(mk);
        if (model == null) {
            throw new ChatRejectException("不支持的模型: " + mk);
        }
        ChatProperties.Effort level = props.effort(ek);
        if (level == null) {
            throw new ChatRejectException("不支持的档位: " + ek);
        }
        return new ModelParams(model.getKey(), level.getTemperature(), level.getMaxTokens(),
                props.thinkingSupported(model.isSupportsThinking()));
    }
}
