package org.leon.authmodule.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenFingerprintTest {

    @DisplayName("指纹固定 12 位十六进制，且相同 token 结果稳定")
    @Test
    void shouldBeStableTwelveHexChars() {
        String fp1 = TokenFingerprint.of("token-value");
        String fp2 = TokenFingerprint.of("token-value");

        assertEquals(fp1, fp2);
        assertEquals(12, fp1.length());
        assertTrue(fp1.matches("[0-9a-f]{12}"));
    }

    @DisplayName("不同 token 得到不同指纹")
    @Test
    void shouldDifferForDifferentTokens() {
        assertNotEquals(TokenFingerprint.of("a"), TokenFingerprint.of("b"));
    }
}
