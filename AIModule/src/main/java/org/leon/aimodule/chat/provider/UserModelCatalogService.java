package org.leon.aimodule.chat.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人模型目录：列出当前用户可选的模型，并把选择器 key 解析成可直发的上游路由。
 * 归属校验只在这里做一次：provider 与 model 都必须属于请求头里的 X-User-Name 且状态启用，
 * 前端传什么 id 都不构成授权。
 *
 * 目录按能力分流：聊天只出 CHAT 模型，生图只出 IMAGE 模型，
 * 否则用户会把生图模型选进对话里，在 /chat/completions 上拿到一个莫名其妙的上游 400。
 */
@Service
public class UserModelCatalogService {

    private final AiUserModelProviderMapper providerMapper;
    private final AiUserModelMapper modelMapper;
    private final ApiKeyCryptoService crypto;

    public UserModelCatalogService(AiUserModelProviderMapper providerMapper, AiUserModelMapper modelMapper,
                                   ApiKeyCryptoService crypto) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.crypto = crypto;
    }

    public record Choice(Long providerId, Long modelId, String providerName, String modelKey,
                         String displayName, boolean supportsThinking) {
        public String selectionKey() {
            return ModelSelection.key(providerId, modelId);
        }
    }

    public List<Choice> choices(String userName) {
        return choices(userName, ModelType.CHAT);
    }

    public List<Choice> imageChoices(String userName) {
        return choices(userName, ModelType.IMAGE);
    }

    private List<Choice> choices(String userName, ModelType wanted) {
        Map<Long, AiUserModelProvider> byId = enabledProviders(userName);
        if (byId.isEmpty()) {
            return List.of();
        }
        List<Choice> out = new ArrayList<>();
        for (AiUserModel model : modelMapper.selectList(new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getUserName, userName)
                .eq(AiUserModel::getStatus, 1)
                .orderByAsc(AiUserModel::getProviderId)
                .orderByAsc(AiUserModel::getId))) {
            AiUserModelProvider provider = byId.get(model.getProviderId());
            if (provider == null || model.getStatus() == null || model.getStatus() != 1) {
                continue;
            }
            if (ModelType.of(model.getModelType()) != wanted || !declares(provider, wanted)) {
                continue;
            }
            out.add(new Choice(provider.getId(), model.getId(), provider.getProviderName(),
                    model.getModelKey(), model.getDisplayName(),
                    Boolean.TRUE.equals(model.getSupportsThinking())));
        }
        return out;
    }

    public ProviderRoute resolve(String userName, String selectionKey) {
        return resolve(userName, selectionKey, ModelType.CHAT);
    }

    public ProviderRoute resolveImage(String userName, String selectionKey) {
        return resolve(userName, selectionKey, ModelType.IMAGE);
    }

    private ProviderRoute resolve(String userName, String selectionKey, ModelType expected) {
        ModelSelection.Ids ids = ModelSelection.parse(selectionKey);
        if (ids == null) {
            throw new ChatRejectException("模型不存在");
        }
        AiUserModelProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiUserModelProvider>()
                .eq(AiUserModelProvider::getId, ids.providerId())
                .eq(AiUserModelProvider::getUserName, userName)
                .eq(AiUserModelProvider::getStatus, 1));
        AiUserModel model = provider == null ? null
                : modelMapper.selectOne(new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getId, ids.modelId())
                .eq(AiUserModel::getProviderId, provider.getId())
                .eq(AiUserModel::getUserName, userName)
                .eq(AiUserModel::getStatus, 1));
        if (model == null || ModelType.of(model.getModelType()) != expected || !declares(provider, expected)) {
            throw new ChatRejectException("模型不存在");
        }
        String apiKey;
        try {
            apiKey = crypto.decrypt(new ApiKeyCryptoService.Encrypted(
                    provider.getApiKeyCiphertext(), provider.getApiKeyNonce()));
        } catch (RuntimeException e) {
            throw new ChatRejectException("供应商密钥不可用，请重新填写 API Key");
        }
        return new ProviderRoute(provider.getId(), model.getId(), provider.getProviderName(),
                model.getDisplayName(), provider.getBaseUrl(), apiKey, model.getModelKey(),
                Boolean.TRUE.equals(model.getSupportsThinking()));
    }

    private Map<Long, AiUserModelProvider> enabledProviders(String userName) {
        Map<Long, AiUserModelProvider> byId = new LinkedHashMap<>();
        for (AiUserModelProvider p : providerMapper.selectList(new LambdaQueryWrapper<AiUserModelProvider>()
                .eq(AiUserModelProvider::getUserName, userName)
                .eq(AiUserModelProvider::getStatus, 1))) {
            if (p.getStatus() == null || p.getStatus() != 1) {
                continue;
            }
            byId.put(p.getId(), p);
        }
        return byId;
    }

    static boolean declares(AiUserModelProvider provider, ModelType modelType) {
        for (ServiceType type : ServiceType.parseCsv(provider.getServiceTypes())) {
            if (type.covers(modelType)) {
                return true;
            }
        }
        return false;
    }
}
