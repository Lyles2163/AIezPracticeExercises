package org.leon.aimodule.imagegen.minio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageObjectKeysTest {

    @Test
    @DisplayName("对象键：{清洗后用户名}/{日期}/{uuid}.{扩展名}")
    void buildsDeterministicKey() {
        String key = ImageObjectKeys.build("Alice", "image/png", LocalDate.of(2026, 11, 6), "abc123");
        assertEquals("alice/20261106/abc123.png", key);
        assertEquals("bob/20261106/abc123.jpg",
                ImageObjectKeys.build("bob", "image/jpeg", LocalDate.of(2026, 11, 6), "abc123"));
    }

    @Test
    @DisplayName("非法字符洗成下划线，并补摘要后缀，避免中文用户名撞成同一个前缀")
    void sanitizesUserName() {
        assertTrue(ImageObjectKeys.sanitizeUser("../leon").startsWith("___leon-"));
        assertTrue(ImageObjectKeys.sanitizeUser(" 张leon ").startsWith("_leon-"));
        assertEquals("_anonymous", ImageObjectKeys.sanitizeUser("  "));
        assertEquals("alice", ImageObjectKeys.sanitizeUser("Alice"));
        assertFalse(ImageObjectKeys.sanitizeUser("张").equals(ImageObjectKeys.sanitizeUser("李")));
    }

    @Test
    @DisplayName("归属判断用同一套清洗规则，且挡掉路径穿越")
    void ownershipCheck() {
        String key = ImageObjectKeys.build("Alice", "image/png", LocalDate.of(2026, 11, 6), "x");
        assertTrue(ImageObjectKeys.ownedBy(key, "alice"));
        assertTrue(ImageObjectKeys.ownedBy("/" + key, "ALICE"));
        assertFalse(ImageObjectKeys.ownedBy(key, "bob"));
        assertFalse(ImageObjectKeys.ownedBy("alice/../../bob/1.png", "alice"));
        assertFalse(ImageObjectKeys.ownedBy("nokey", "alice"));
        assertFalse(ImageObjectKeys.ownedBy(null, "alice"));
    }
}
