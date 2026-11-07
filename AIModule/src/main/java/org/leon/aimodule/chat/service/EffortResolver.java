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
        return resolve(modelKey, effort, null);
    }

    /**
     * @param userThinking 用户在前端按钮上的选择：null=跟随模型能力；
     *                     true/false=显式开启/关闭。即便用户要求开启，
     *                     全局开关或模型不支持时也只能 false（宁缺毋滥）。
     */
    public ModelParams resolve(String modelKey, String effort, Boolean userThinking) {
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
        boolean base = props.thinkingSupported(model.isSupportsThinking());
        boolean thinking = userThinking == null ? base : (base && userThinking);
        return new ModelParams(model.getKey(), level.getTemperature(), level.getMaxTokens(), thinking);
    }

    /**
     * 个人供应商的模型不在配置表里，只能按档位算参数，thinking 能力取用户填的模型标记，
     * 再与全局开关相与——口径与内置模型完全一致，否则同一个按钮会出现两种行为。
     */
    public ModelParams resolvePersonal(String selectionKey, String effort, boolean supportsThinking,
                                       Boolean userThinking) {
        String ek = (effort == null || effort.isBlank()) ? props.getDefaultEffort() : effort.trim();
        ChatProperties.Effort level = props.effort(ek);
        if (level == null) {
            throw new ChatRejectException("不支持的档位: " + ek);
        }
        boolean base = props.thinkingSupported(supportsThinking);
        boolean thinking = userThinking == null ? base : (base && userThinking);
        return new ModelParams(selectionKey, level.getTemperature(), level.getMaxTokens(), thinking);
    }
}
