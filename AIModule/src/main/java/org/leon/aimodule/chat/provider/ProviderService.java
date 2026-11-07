package org.leon.aimodule.chat.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class ProviderService {
    private static final String PROTOCOL = "OPENAI_COMPAT";

    private final AiUserModelProviderMapper providerMapper;
    private final AiUserModelMapper modelMapper;
    private final ApiKeyCryptoService crypto;
    private final ProviderProperties properties;
    private final ProviderProbe probe;

    public ProviderService(AiUserModelProviderMapper providerMapper, AiUserModelMapper modelMapper,
                           ApiKeyCryptoService crypto, ProviderProperties properties, ProviderProbe probe) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.crypto = crypto;
        this.properties = properties;
        this.probe = probe;
    }

    /**
     * 探活按模型类型分流：聊天用 /chat/completions 打一发便宜请求，
     * 生图/RAG 只用 GET /models —— 对生图端点发真生成是花钱的，
     * 对 embedding 端点发 chat/completions 则必然 400。
     */
    public ProviderProbe.Result testConnection(String userName, Long providerId) {
        AiUserModelProvider provider = ownedProvider(userName, providerId);
        List<AiUserModel> enabled = modelsOf(userName, providerId).stream()
                .filter(m -> m.getStatus() != null && m.getStatus() == 1)
                .toList();
        if (enabled.isEmpty()) {
            throw new ChatRejectException("请先添加至少一个模型");
        }
        String apiKey = crypto.decrypt(new ApiKeyCryptoService.Encrypted(
                provider.getApiKeyCiphertext(), provider.getApiKeyNonce()));
        AiUserModel first = enabled.get(0);
        if (ModelType.of(first.getModelType()).isChat()) {
            return probe.probe(provider.getBaseUrl(), apiKey, first.getModelKey());
        }
        return probe.probeModels(provider.getBaseUrl(), apiKey);
    }

    public List<ProviderViews.Provider> list(String userName) {
        List<AiUserModelProvider> providers = providerMapper.selectList(new LambdaQueryWrapper<AiUserModelProvider>()
                .eq(AiUserModelProvider::getUserName, userName)
                .orderByDesc(AiUserModelProvider::getUpdateTime)
                .orderByDesc(AiUserModelProvider::getId));
        return providers.stream().map(p -> view(p, modelsOf(userName, p.getId()))).toList();
    }

    @Transactional
    public ProviderViews.Provider create(String userName, ProviderRequests.CreateProvider req) {
        long count = providerMapper.selectCount(new LambdaQueryWrapper<AiUserModelProvider>()
                .eq(AiUserModelProvider::getUserName, userName));
        if (count >= properties.getMaxProvidersPerUser()) {
            throw new ChatRejectException("供应商数量已达上限");
        }
        String baseUrl = BaseUrlGuard.normalize(req.baseUrl(), properties.isAllowHttp());
        List<ServiceType> serviceTypes = ServiceType.parseStrict(req.serviceTypes());
        validateModels(req.models());
        for (ProviderRequests.ModelInput input : req.models()) {
            requireDeclared(serviceTypes, ModelType.parseStrict(input.modelType()));
        }
        ApiKeyCryptoService.Encrypted key = crypto.encrypt(req.apiKey().trim());
        AiUserModelProvider provider = new AiUserModelProvider();
        provider.setUserName(userName);
        provider.setProviderName(req.providerName().trim());
        provider.setProtocol(PROTOCOL);
        provider.setBaseUrl(baseUrl);
        provider.setServiceTypes(ServiceType.toCsv(serviceTypes));
        provider.setApiKeyCiphertext(key.ciphertext());
        provider.setApiKeyNonce(key.nonce());
        provider.setApiKeyFingerprint(crypto.fingerprint(req.apiKey().trim()));
        provider.setStatus(1);
        providerMapper.insert(provider);
        insertModels(userName, provider.getId(), req.models());
        return view(provider, modelsOf(userName, provider.getId()));
    }

    @Transactional
    public ProviderViews.Provider update(String userName, Long id, ProviderRequests.UpdateProvider req) {
        AiUserModelProvider provider = ownedProvider(userName, id);
        provider.setProviderName(req.providerName().trim());
        provider.setBaseUrl(BaseUrlGuard.normalize(req.baseUrl(), properties.isAllowHttp()));
        provider.setStatus(normalizeStatus(req.status()));
        if (req.serviceTypes() != null && !req.serviceTypes().isEmpty()) {
            List<ServiceType> wanted = ServiceType.parseStrict(req.serviceTypes());
            requireNothingOrphaned(modelsOf(userName, id), wanted);
            provider.setServiceTypes(ServiceType.toCsv(wanted));
        }
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            ApiKeyCryptoService.Encrypted key = crypto.encrypt(req.apiKey().trim());
            provider.setApiKeyCiphertext(key.ciphertext());
            provider.setApiKeyNonce(key.nonce());
            provider.setApiKeyFingerprint(crypto.fingerprint(req.apiKey().trim()));
        }
        providerMapper.updateById(provider);
        return view(provider, modelsOf(userName, id));
    }

    @Transactional
    public void delete(String userName, Long id) {
        ownedProvider(userName, id);
        AiUserModelProvider update = new AiUserModelProvider();
        update.setId(id);
        update.setStatus(0);
        providerMapper.updateById(update);
        AiUserModel model = new AiUserModel();
        model.setStatus(0);
        modelMapper.update(model, new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getProviderId, id).eq(AiUserModel::getUserName, userName));
    }

    @Transactional
    public ProviderViews.Model addModel(String userName, Long providerId, ProviderRequests.ModelInput req) {
        AiUserModelProvider provider = ownedProvider(userName, providerId);
        if (provider.getStatus() != 1) {
            throw new ChatRejectException("供应商已停用");
        }
        long count = modelMapper.selectCount(new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getProviderId, providerId).eq(AiUserModel::getUserName, userName));
        if (count >= properties.getMaxModelsPerProvider()) {
            throw new ChatRejectException("模型数量已达上限");
        }
        requireDeclared(capabilities(provider), ModelType.parseStrict(req.modelType()));
        AiUserModel model = new AiUserModel();
        fillModel(model, userName, providerId, req);
        modelMapper.insert(model);
        return modelView(model);
    }

    @Transactional
    public ProviderViews.Model updateModel(String userName, Long providerId, Long modelId,
                                           ProviderRequests.UpdateModel req) {
        AiUserModelProvider provider = ownedProvider(userName, providerId);
        AiUserModel model = ownedModel(userName, providerId, modelId);
        requireDeclared(capabilities(provider), ModelType.parseStrict(req.modelType()));
        fillModel(model, userName, providerId, req);
        model.setStatus(normalizeStatus(req.status()));
        modelMapper.updateById(model);
        return modelView(model);
    }

    @Transactional
    public void deleteModel(String userName, Long providerId, Long modelId) {
        ownedProvider(userName, providerId);
        AiUserModel model = ownedModel(userName, providerId, modelId);
        model.setStatus(0);
        modelMapper.updateById(model);
    }

    private void insertModels(String userName, Long providerId, List<ProviderRequests.ModelInput> inputs) {
        for (ProviderRequests.ModelInput input : inputs) {
            AiUserModel model = new AiUserModel();
            fillModel(model, userName, providerId, input);
            modelMapper.insert(model);
        }
    }

    private void fillModel(AiUserModel model, String userName, Long providerId, ProviderRequests.ModelData input) {
        model.setProviderId(providerId);
        model.setUserName(userName);
        model.setModelKey(input.modelKey().trim());
        model.setDisplayName(input.displayName().trim());
        model.setSupportsThinking(Boolean.TRUE.equals(input.supportsThinking()));
        model.setModelType(ModelType.parseStrict(input.modelType()).name());
        model.setStatus(1);
    }

    private void validateModels(List<ProviderRequests.ModelInput> models) {
        if (models == null || models.isEmpty() || models.size() > properties.getMaxModelsPerProvider()) {
            throw new ChatRejectException("模型数量不合法");
        }
    }

    /** 模型类型必须被供应商声明的服务能力覆盖，否则等于存了一条永远调不通的路由。 */
    private static void requireDeclared(List<ServiceType> declared, ModelType modelType) {
        for (ServiceType type : declared) {
            if (type.covers(modelType)) {
                return;
            }
        }
        throw new ChatRejectException("该供应商未声明" + serviceLabel(modelType.serviceType()) + "服务");
    }

    /**
     * 摘掉某项能力前先看有没有模型还挂在它下面，否则那些模型会变成孤儿：
     * 设置页能看见，用的时候永远「模型不存在」。
     */
    private static void requireNothingOrphaned(List<AiUserModel> models, List<ServiceType> wanted) {
        Set<ServiceType> keep = wanted.isEmpty() ? EnumSet.noneOf(ServiceType.class) : EnumSet.copyOf(wanted);
        for (AiUserModel model : models) {
            if (model.getStatus() == null || model.getStatus() != 1) {
                continue;
            }
            ServiceType needed = ModelType.of(model.getModelType()).serviceType();
            if (!keep.contains(needed)) {
                throw new ChatRejectException("该供应商下还有" + serviceLabel(needed)
                        + "模型，不能取消" + serviceLabel(needed) + "能力");
            }
        }
    }

    private static String serviceLabel(ServiceType type) {
        return switch (type) {
            case CHAT -> "聊天";
            case IMAGE -> "生图";
            case RAG -> "RAG";
        };
    }

    private List<AiUserModel> modelsOf(String userName, Long providerId) {
        return modelMapper.selectList(new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getProviderId, providerId).eq(AiUserModel::getUserName, userName)
                .orderByAsc(AiUserModel::getId));
    }

    private AiUserModelProvider ownedProvider(String userName, Long id) {
        AiUserModelProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiUserModelProvider>()
                .eq(AiUserModelProvider::getId, id).eq(AiUserModelProvider::getUserName, userName));
        if (provider == null) {
            throw new ChatRejectException("供应商不存在");
        }
        return provider;
    }

    private AiUserModel ownedModel(String userName, Long providerId, Long modelId) {
        AiUserModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getId, modelId).eq(AiUserModel::getProviderId, providerId)
                .eq(AiUserModel::getUserName, userName));
        if (model == null) {
            throw new ChatRejectException("模型不存在");
        }
        return model;
    }

    static List<ServiceType> capabilities(AiUserModelProvider provider) {
        return ServiceType.parseCsv(provider.getServiceTypes());
    }

    private ProviderViews.Provider view(AiUserModelProvider provider, List<AiUserModel> models) {
        String masked = "********";
        try {
            String key = crypto.decrypt(new ApiKeyCryptoService.Encrypted(
                    provider.getApiKeyCiphertext(), provider.getApiKeyNonce()));
            masked = crypto.mask(key);
        } catch (RuntimeException ignored) {
            // 密文损坏时仍允许设置页显示连接记录，但不泄露内部解密错误。
        }
        List<String> serviceTypes = new ArrayList<>();
        for (ServiceType type : capabilities(provider)) {
            serviceTypes.add(type.name());
        }
        return new ProviderViews.Provider(provider.getId(), provider.getProviderName(), provider.getProtocol(),
                provider.getBaseUrl(), serviceTypes, provider.getApiKeyCiphertext() != null, masked,
                provider.getStatus() == null ? 0 : provider.getStatus(), models.stream().map(this::modelView).toList());
    }

    private ProviderViews.Model modelView(AiUserModel model) {
        return new ProviderViews.Model(model.getId(), model.getModelKey(), model.getDisplayName(),
                ModelType.of(model.getModelType()).name(),
                Boolean.TRUE.equals(model.getSupportsThinking()), model.getStatus() == null ? 0 : model.getStatus());
    }

    private static int normalizeStatus(Integer status) {
        return status != null && status == 0 ? 0 : 1;
    }
}
