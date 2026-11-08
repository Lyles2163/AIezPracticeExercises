package org.leon.aimodule.imagegen.web.dto;

import java.util.List;

/** 生图模型目录：只收 model_type=IMAGE 且供应商声明了生图能力的模型。 */
public record ImageModelCatalogVo(List<Model> models) {

    public record Model(String key, String name, String tag) {
    }
}
