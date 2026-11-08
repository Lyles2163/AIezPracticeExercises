package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTypeTest {

    @Test
    @DisplayName("老数据没有 service_types，一律按 CHAT")
    void nullMeansChat() {
        assertEquals(List.of(ServiceType.CHAT), ServiceType.parseCsv(null));
        assertEquals(List.of(ServiceType.CHAT), ServiceType.parseCsv("  "));
        assertEquals(List.of(ServiceType.CHAT), ServiceType.parseCsv("MUSIC"));
    }

    @Test
    @DisplayName("CSV 去重、大写归一、顺序固定")
    void normalizesCsv() {
        assertEquals(List.of(ServiceType.CHAT, ServiceType.IMAGE),
                ServiceType.parseCsv("image, CHAT ,image"));
        assertEquals("CHAT,IMAGE,RAG",
                ServiceType.toCsv(ServiceType.parseCsv("rag,image,chat")));
    }

    @Test
    @DisplayName("严格解析：请求里写了不认识的能力直接拒绝")
    void strictRejectsUnknown() {
        assertEquals("未知的服务能力: music",
                assertThrows(ChatRejectException.class,
                        () -> ServiceType.parseStrict(List.of("music"))).getMessage());
        assertEquals(List.of(ServiceType.CHAT),
                ServiceType.parseStrict(List.of()));
    }

    @Test
    @DisplayName("能力覆盖：RAG 同时覆盖 embedding 与 rerank")
    void coversModelTypes() {
        assertTrue(ServiceType.CHAT.covers(ModelType.CHAT));
        assertTrue(ServiceType.IMAGE.covers(ModelType.IMAGE));
        assertTrue(ServiceType.RAG.covers(ModelType.EMBEDDING));
        assertTrue(ServiceType.RAG.covers(ModelType.RERANK));
        assertFalse(ServiceType.CHAT.covers(ModelType.IMAGE));
        assertFalse(ServiceType.IMAGE.covers(ModelType.EMBEDDING));
    }
}
