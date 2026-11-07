package org.leon.aimodule.chat.service;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.provider.ModelSelection;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.provider.UserModelCatalogService;
import org.springframework.stereotype.Service;

/**
 * (userName, modelKey, effort) → 调用参数 + 上游路由。
 * 两条链在这里合流：内置模型只出 ModelParams（route 为 null，走服务端 apiKey/baseUrl），
 * 个人模型额外带一条 ProviderRoute，且 route 只能由服务端按 X-User-Name 解析出来。
 */
@Service
public class ModelResolver {

    private final ChatProperties props;
    private final EffortResolver efforts;
    private final UserModelCatalogService userModels;

    public ModelResolver(ChatProperties props, EffortResolver efforts, UserModelCatalogService userModels) {
        this.props = props;
        this.efforts = efforts;
        this.userModels = userModels;
    }

    public record Resolved(ModelParams params, ProviderRoute route) {
    }

    public Resolved resolve(String userName, String modelKey, String effort, Boolean userThinking) {
        String mk = (modelKey == null || modelKey.isBlank()) ? props.getDefaultModel() : modelKey.trim();
        if (ModelSelection.isPersonal(mk)) {
            ProviderRoute route = userModels.resolve(userName, mk);
            return new Resolved(
                    efforts.resolvePersonal(mk, effort, route.supportsThinking(), userThinking), route);
        }
        return new Resolved(efforts.resolve(mk, effort, userThinking), null);
    }

    public Resolved resolve(String userName, String modelKey, String effort) {
        return resolve(userName, modelKey, effort, null);
    }
}
