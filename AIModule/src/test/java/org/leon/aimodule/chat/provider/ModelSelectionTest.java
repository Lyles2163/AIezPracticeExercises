package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSelectionTest {

    @Test
    @DisplayName("内置模型 key 不是个人模型")
    void builtinIsNotPersonal() {
        assertFalse(ModelSelection.isPersonal("deepseek-chat"));
        assertFalse(ModelSelection.isPersonal(null));
        assertNull(ModelSelection.parse("deepseek-chat"));
    }

    @Test
    @DisplayName("个人模型 key 往返一致，且长度不超 ai_conversation.model_key 的 64")
    void roundTrip() {
        String key = ModelSelection.key(12L, 34L);
        assertEquals("u:12:34", key);
        assertTrue(key.length() <= 64);
        assertTrue(ModelSelection.isPersonal(key));
        assertEquals(12L, ModelSelection.parse(key).providerId());
        assertEquals(34L, ModelSelection.parse(key).modelId());
    }

    @Test
    @DisplayName("脏 key 解析为 null 而不是抛异常")
    void malformedParsesToNull() {
        assertNull(ModelSelection.parse("u:"));
        assertNull(ModelSelection.parse("u:1"));
        assertNull(ModelSelection.parse("u:a:b"));
        assertNull(ModelSelection.parse("u:1:2:3"));
    }
}
