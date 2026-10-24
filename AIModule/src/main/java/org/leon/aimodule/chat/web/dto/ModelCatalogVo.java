package org.leon.aimodule.chat.web.dto;

import java.util.List;

public record ModelCatalogVo(String defaultModel, String defaultEffort,
                             List<String> efforts, List<ModelVo> models) {
}
