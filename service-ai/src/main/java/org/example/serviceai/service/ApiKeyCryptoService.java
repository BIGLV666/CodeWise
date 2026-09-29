package org.example.serviceai.service;

import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
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
            throw new BizException(AiErrorCode.MASTER_KEY_CONFIG_INVALID);
        }
        byte[]keyBytes ;
        try {
            keyBytes = Base64.getDecoder().decode(base64MasterKey);
        }catch (IllegalArgumentException e){
            throw new BizException(
                    AiErrorCode.MASTER_KEY_CONFIG_INVALID,
                    e
            );
        }
        if(keyBytes.length!=32){
            throw new BizException(AiErrorCode.MASTER_KEY_CONFIG_INVALID);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }
    /**
     * 加密
     */
    public String encrypt(String plainApiKey)
    {
        if (plainApiKey == null || plainApiKey.isBlank()) {
            throw new BizException(AiErrorCode.API_KEY_REQUIRED);
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
            throw new BizException(AiErrorCode.API_KEY_ENCRYPT_FAILED, exception);
        }
    }
    /**
     * 把数据库中的密文还原成原始 API Key。
     */
    public String decrypt(String encryptedApiKey) {
        if (encryptedApiKey == null || encryptedApiKey.isBlank()) {
            throw new BizException(AiErrorCode.API_KEY_DECRYPT_FAILED);
        }

        try {
            String[] parts = encryptedApiKey.split("\\.", 2);

            if (parts.length != 2) {
                throw new BizException(
                        AiErrorCode.API_KEY_DECRYPT_FAILED
                );
            }

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encryptedBytes = Base64.getDecoder().decode(parts[1]);

            if (iv.length != IV_LENGTH) {
                throw new BizException(
                        AiErrorCode.API_KEY_DECRYPT_FAILED
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
            throw new BizException(AiErrorCode.API_KEY_DECRYPT_FAILED, exception);
        }
    }

}
