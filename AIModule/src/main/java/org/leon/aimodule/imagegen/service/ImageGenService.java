package org.leon.aimodule.imagegen.service;

import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.provider.UserModelCatalogService;
import org.leon.aimodule.imagegen.config.ImageGenProperties;
import org.leon.aimodule.imagegen.exception.ImageGenException;
import org.leon.aimodule.imagegen.gateway.ImageGateway;
import org.leon.aimodule.imagegen.minio.ImageObjectKeys;
import org.leon.aimodule.imagegen.minio.ImageObjectStore;
import org.leon.aimodule.imagegen.web.dto.ImageGenRequest;
import org.leon.aimodule.imagegen.web.dto.ImageGenVo;
import org.leon.aimodule.imagegen.web.dto.ImageModelCatalogVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ImageGenService {

    /** 只放行 OpenAI 兼容端点普遍支持的几档尺寸；不传就交给上游默认值。 */
    static final Set<String> ALLOWED_SIZES = Set.of(
            "256x256", "512x512", "1024x1024", "1024x1792", "1792x1024");

    static final String FILE_PATH = "/ai/imagegen/files/";

    private final UserModelCatalogService userModels;
    private final ImageGateway gateway;
    private final ImageObjectStore store;
    private final ImageGenProperties properties;

    public ImageGenService(UserModelCatalogService userModels, ImageGateway gateway,
                           ImageObjectStore store, ImageGenProperties properties) {
        this.userModels = userModels;
        this.gateway = gateway;
        this.store = store;
        this.properties = properties;
    }

    public ImageModelCatalogVo models(String userName) {
        List<ImageModelCatalogVo.Model> models = userModels.imageChoices(userName).stream()
                .map(c -> new ImageModelCatalogVo.Model(c.selectionKey(), c.displayName(), c.providerName()))
                .toList();
        return new ImageModelCatalogVo(models);
    }

    public ImageGenVo generate(String userName, ImageGenRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        if (prompt.isEmpty()) {
            throw new ImageGenException("请填写提示词");
        }
        if (prompt.length() > properties.getLimits().getMaxPromptChars()) {
            throw new ImageGenException("提示词最多 " + properties.getLimits().getMaxPromptChars() + " 个字符");
        }
        int n = normalizeCount(request.n());
        String size = normalizeSize(request.size());

        ProviderRoute route = userModels.resolveImage(userName, request.modelKey());
        ImageGateway.Response response = gateway.generate(new ImageGateway.Command(
                route.baseUrl(), route.apiKey(), route.upstreamModel(), prompt, size, n,
                properties.getLimits().getRequestTimeoutSeconds()));

        List<ImageGenVo.Image> images = new ArrayList<>(response.images().size());
        for (ImageGateway.GeneratedImage image : response.images()) {
            byte[] data = image.data();
            if (data == null || data.length == 0) {
                continue;
            }
            if (data.length > properties.getLimits().getMaxImageBytes()) {
                throw new ImageGenException("上游返回的图片过大");
            }
            String objectKey = store.put(userName, image.contentType(), data);
            images.add(new ImageGenVo.Image(objectKey, FILE_PATH + objectKey, data.length, image.contentType()));
        }
        if (images.isEmpty()) {
            throw new ImageGenException("上游没有返回图片");
        }
        return new ImageGenVo(request.modelKey(), response.latencyMs(), images);
    }

    /** 越权一律按"不存在"处理：告诉越权者这个 key 存在本身就是信息泄露。 */
    public ImageObjectStore.StoredImage file(String userName, String objectKey) {
        if (!ImageObjectKeys.ownedBy(objectKey, userName)) {
            throw new ImageGenException("图片不存在");
        }
        return store.open(objectKey);
    }

    private int normalizeCount(Integer n) {
        int count = n == null ? 1 : n;
        if (count < 1) {
            throw new ImageGenException("生成数量不合法");
        }
        if (count > properties.getLimits().getMaxImagesPerRequest()) {
            throw new ImageGenException("一次最多生成 " + properties.getLimits().getMaxImagesPerRequest() + " 张");
        }
        return count;
    }

    private static String normalizeSize(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        String trimmed = size.trim().toLowerCase();
        if (!ALLOWED_SIZES.contains(trimmed)) {
            throw new ImageGenException("不支持的图片尺寸: " + size);
        }
        return trimmed;
    }
}
