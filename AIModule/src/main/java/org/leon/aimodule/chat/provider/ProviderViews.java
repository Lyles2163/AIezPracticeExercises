package org.leon.aimodule.chat.provider;

import java.util.List;

public final class ProviderViews {
    private ProviderViews() {
    }

    public record Provider(Long id, String providerName, String protocol, String baseUrl,
                           List<String> serviceTypes, boolean configured, String maskedKey,
                           int status, List<Model> models) {
    }

    public record Model(Long id, String modelKey, String displayName, String modelType,
                        boolean supportsThinking, int status) {
    }
}
