package org.example.serviceai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ApiKeyCryptoService {
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyCryptoService( @Value("${security.api-key-master-key}") String base64MasterKey)
    {
        if(base64MasterKey==null||base64MasterKey.isBlank())
        {
            throw new IllegalArgumentException("API Key 加密主密钥不能为空");
        }
        byte[]keyBytes ;
        try {
            keyBytes = Base64.getDecoder().decode(base64MasterKey);
        }catch (IllegalArgumentException e){
            throw new IllegalArgumentException(
                    "API Key 加密主密钥必须是 Base64 格式",
                    e
            );
        }
        if(keyBytes.length!=32){
            throw new IllegalArgumentException(
                    "AES-256 主密钥解码后必须是 32 字节，当前是 "
                            + keyBytes.length
                            + " 字节"
            );
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }
    /**
     * 加密
     */
    public String encrypt(String plainApiKey)
    {
        if (plainApiKey == null || plainApiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }

        try {
            // 每次加密都要重新生成一个随机 IV。
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            GCMParameterSpec parameterSpec =
                    new GCMParameterSpec(TAG_LENGTH, iv);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    parameterSpec
            );

            byte[] encryptedBytes = cipher.doFinal(
                    plainApiKey.getBytes(StandardCharsets.UTF_8)
            );

            String encodedIv = Base64.getEncoder().encodeToString(iv);
            String encodedCiphertext = Base64.getEncoder()
                    .encodeToString(encryptedBytes);

            // 将 IV 和密文拼在一起保存。
            return encodedIv + "." + encodedCiphertext;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("API Key 加密失败", exception);
        }
    }
    /**
     * 把数据库中的密文还原成原始 API Key。
     */
    public String decrypt(String encryptedApiKey) {
        if (encryptedApiKey == null || encryptedApiKey.isBlank()) {
            throw new IllegalArgumentException("加密后的 API Key 不能为空");
        }

        try {
            String[] parts = encryptedApiKey.split("\\.", 2);

            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "API Key 密文格式不正确"
                );
            }

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encryptedBytes = Base64.getDecoder().decode(parts[1]);

            if (iv.length != IV_LENGTH) {
                throw new IllegalArgumentException(
                        "API Key 密文中的 IV 长度不正确"
                );
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            GCMParameterSpec parameterSpec =
                    new GCMParameterSpec(TAG_LENGTH, iv);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    parameterSpec
            );

            byte[] plainBytes = cipher.doFinal(encryptedBytes);

            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("API Key 解密失败", exception);
        }
    }

}
