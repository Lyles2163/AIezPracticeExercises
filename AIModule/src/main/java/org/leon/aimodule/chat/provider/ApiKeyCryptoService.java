package org.leon.aimodule.chat.provider;

import org.leon.aimodule.chat.exception.ChatRejectException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ApiKeyCryptoService {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String masterKey;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ApiKeyCryptoService(ProviderProperties props) {
        this(props.getEncryptionKey());
    }

    ApiKeyCryptoService(String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey.trim();
    }

    public Encrypted encrypt(String plainText) {
        requireConfigured();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(encode(ciphertext), encode(nonce));
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    public String decrypt(Encrypted encrypted) {
        requireConfigured();
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(encrypted.nonce())));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted.ciphertext())),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败", e);
        }
    }

    public String fingerprint(String plainText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plainText.getBytes(StandardCharsets.UTF_8));
            return encode(digest).replace("+", "-").replace("/", "_").replace("=", "");
        } catch (Exception e) {
            throw new IllegalStateException("API Key 指纹生成失败", e);
        }
    }

    public String mask(String plainText) {
        if (plainText == null || plainText.length() <= 8) {
            return "********";
        }
        return plainText.substring(0, 4) + "..." + plainText.substring(plainText.length() - 4);
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private void requireConfigured() {
        if (masterKey.isBlank()) {
            // 业务拒绝而不是 IllegalStateException：这是"部署少配了一项"，
            // 设置页要看得到原因，而不是收到一句笼统的「服务内部错误」。
            throw new ChatRejectException("服务端未配置 API Key 加密主密钥（EZ_PROVIDER_ENCRYPTION_KEY），服务商功能暂不可用");
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public record Encrypted(String ciphertext, String nonce) {
    }
}
