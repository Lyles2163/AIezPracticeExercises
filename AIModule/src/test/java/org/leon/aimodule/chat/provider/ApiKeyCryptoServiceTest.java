package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ApiKeyCryptoServiceTest {

    @Test
    void encryptsAndDecryptsApiKeyWithoutStoringPlaintext() {
        ApiKeyCryptoService service = new ApiKeyCryptoService(
                "0123456789abcdef0123456789abcdef");

        ApiKeyCryptoService.Encrypted encrypted = service.encrypt("sk-secret-value");

        assertNotEquals("sk-secret-value", encrypted.ciphertext());
        assertEquals("sk-secret-value", service.decrypt(encrypted));
        assertEquals("sk-s...alue", service.mask("sk-secret-value"));
    }
}
