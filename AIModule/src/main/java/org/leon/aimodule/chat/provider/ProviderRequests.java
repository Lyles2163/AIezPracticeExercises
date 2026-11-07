package org.leon.aimodule.chat.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ProviderRequests {
    private ProviderRequests() {
    }

    public record CreateProvider(
            @NotBlank @Size(max = 80) String providerName,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 4096) String apiKey,
            /** 服务能力：CHAT / IMAGE / RAG，可多选。不传按 CHAT。 */
            @Size(max = 3) List<String> serviceTypes,
            @Valid @Size(min = 1, max = 30) List<ModelInput> models) {
    }

    public record UpdateProvider(
            @NotBlank @Size(max = 80) String providerName,
            @NotBlank @Size(max = 500) String baseUrl,
            @Size(max = 4096) String apiKey,
            /** 不传就保持原能力集合，绝不能被重置成 CHAT。 */
            @Size(max = 3) List<String> serviceTypes,
            Integer status) {
    }

    /** 新增与更新共用同一套模型字段，避免两处各写一遍 trim/默认值。 */
    public sealed interface ModelData permits ModelInput, UpdateModel {
        String modelKey();

        String displayName();

        Boolean supportsThinking();

        /** CHAT / IMAGE / EMBEDDING / RERANK。不传按 CHAT。 */
        String modelType();
    }

    public record ModelInput(
            @NotBlank @Size(max = 128) String modelKey,
            @NotBlank @Size(max = 120) String displayName,
            Boolean supportsThinking,
            @Size(max = 32) String modelType) implements ModelData {
    }

    public record UpdateModel(
            @NotBlank @Size(max = 128) String modelKey,
            @NotBlank @Size(max = 120) String displayName,
            Boolean supportsThinking,
            @Size(max = 32) String modelType,
            Integer status) implements ModelData {
    }
}
