package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelTypeTest {

    @Test
    @DisplayName("读库路径：null / 历史脏值一律当 CHAT，绝不抛错")
    void readPathDefaultsToChat() {
        assertEquals(ModelType.CHAT, ModelType.of(null));
        assertEquals(ModelType.CHAT, ModelType.of("LEGACY-UNKNOWN"));
        assertEquals(ModelType.IMAGE, ModelType.of("image"));
    }

    @Test
    @DisplayName("请求路径：不认识的值直接拒绝")
    void strictRejectsUnknown() {
        assertEquals("未知的模型类型: audio",
                assertThrows(ChatRejectException.class,
                        () -> ModelType.parseStrict("audio")).getMessage());
        assertEquals(ModelType.CHAT, ModelType.parseStrict(null));
    }

    @Test
    @DisplayName("模型类型映射到供应商能力")
    void mapsToServiceType() {
        assertEquals(ServiceType.CHAT, ModelType.CHAT.serviceType());
        assertEquals(ServiceType.IMAGE, ModelType.IMAGE.serviceType());
        assertEquals(ServiceType.RAG, ModelType.EMBEDDING.serviceType());
        assertEquals(ServiceType.RAG, ModelType.RERANK.serviceType());
    }
}
