package com.ruoyi.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CollectorSecretCrypto
{
    private static final SecureRandom RANDOM = new SecureRandom();

    private CollectorSecretCrypto() {}

    public static String generateSecret()
    {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String encrypt(String plaintext, String masterKey)
    {
        try
        {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(masterKey), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Failed to encrypt collector secret", ex);
        }
    }

    public static String decrypt(String ciphertext, String masterKey)
    {
        try
        {
            byte[] value = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOfRange(value, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(value, 12, value.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(masterKey), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Failed to decrypt collector secret", ex);
        }
    }

    public static String hmacHex(String secret, String canonical)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Failed to calculate HMAC", ex);
        }
    }

    public static String sha256Hex(byte[] value)
    {
        try
        {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Failed to calculate SHA-256", ex);
        }
    }

    private static SecretKeySpec key(String masterKey) throws Exception
    {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
