package com.eneve.agent.settings;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * AES-256-GCM encryption for secrets stored in the agent_settings table.
 *
 * The master key is provided via the SETTINGS_ENCRYPTION_KEY environment variable
 * as a 64-character hex string (32 bytes). Generate one with: openssl rand -hex 32
 *
 * Storage format: Base64(IV[12] || ciphertext || GCM-tag[16])
 * All components are concatenated before Base64 encoding so the stored value is
 * a single opaque string with no separator.
 */
@ApplicationScoped
public class SettingsEncryption {

    private static final Logger LOG = Logger.getLogger(SettingsEncryption.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS  = 128;

    @ConfigProperty(name = "settings.encryption.key", defaultValue = "")
    String hexKey;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        if (hexKey == null || hexKey.isBlank()) {
            LOG.warn("settings.encryption.key is not configured — secret storage is disabled. "
                    + "Set SETTINGS_ENCRYPTION_KEY to enable encrypted settings.");
            return;
        }
        String trimmed = hexKey.trim();
        if (trimmed.length() != 64) {
            throw new IllegalStateException(
                    "SETTINGS_ENCRYPTION_KEY must be exactly 64 hex characters (32 bytes). "
                    + "Generate one with: openssl rand -hex 32");
        }
        byte[] keyBytes = hexToBytes(trimmed);
        secretKey = new SecretKeySpec(keyBytes, "AES");
        LOG.info("SettingsEncryption initialised (AES-256-GCM)");
    }

    public boolean isConfigured() {
        return secretKey != null;
    }

    /**
     * Encrypts {@code plaintext} and returns Base64(IV || ciphertext || tag).
     * Throws {@link IllegalStateException} when the master key is not configured.
     */
    public String encrypt(String plaintext) {
        requireConfigured();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertextWithTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertextWithTag, 0, combined, iv.length, ciphertextWithTag.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String)}.
     * Throws {@link IllegalStateException} when the master key is not configured.
     */
    public String decrypt(String encoded) {
        requireConfigured();
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);

            byte[] iv             = new byte[GCM_IV_LENGTH];
            byte[] ciphertextWithTag = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertextWithTag, 0, ciphertextWithTag.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] plainBytes = cipher.doFinal(ciphertextWithTag);
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed — data may be corrupt or the key may have changed", e);
        }
    }

    private void requireConfigured() {
        if (secretKey == null) {
            throw new IllegalStateException(
                    "Secret storage requires SETTINGS_ENCRYPTION_KEY to be configured.");
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
