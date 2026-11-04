package com.waypoint.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets stored in MongoDB (Google refresh tokens, OAuth PKCE verifiers).
 *
 * <p>Every value gets a fresh random 96-bit IV. The caller must supply associated data (AAD); it is authenticated
 * but not stored, so a ciphertext only decrypts for the same AAD. Callers pass the organization id, which binds a
 * stored token to its tenant: a value copied into another organization's document fails to decrypt.</p>
 *
 * <p>The key is 32 random bytes, Base64-encoded, supplied through configuration. Its id is stored with each value so
 * a later key rotation can tell which key encrypted what. Nothing here logs, and no exception message contains key,
 * plaintext or ciphertext.</p>
 */
public final class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key standard Base64 of exactly 32 bytes
     * @param keyId     identifier stored with every encrypted value
     */
    public TokenCipher(String base64Key, String keyId) {
        if (keyId == null || keyId.isBlank()) throw new TokenCipherException("Token encryption key id is required");
        this.keyId = keyId.trim();
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    private TokenCipher() {
        this.key = null;
        this.keyId = null;
    }

    /** A cipher with no key. {@link #isConfigured()} is false and every operation fails. */
    public static TokenCipher unconfigured() {
        return new TokenCipher();
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String keyId() {
        return keyId;
    }

    public EncryptedValue encrypt(String plaintext, String associatedData) {
        requireConfigured();
        if (plaintext == null || plaintext.isEmpty()) throw new TokenCipherException("Nothing to encrypt");
        byte[] aad = aad(associatedData);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv), keyId);
        } catch (GeneralSecurityException failure) {
            throw new TokenCipherException("Token could not be encrypted");
        }
    }

    public String decrypt(EncryptedValue value, String associatedData) {
        requireConfigured();
        if (value == null || value.ciphertext() == null || value.iv() == null) {
            throw new TokenCipherException("Token could not be decrypted");
        }
        if (!keyId.equals(value.keyId())) throw new TokenCipherException("Token was encrypted with an unknown key id");
        byte[] aad = aad(associatedData);
        try {
            byte[] iv = Base64.getDecoder().decode(value.iv());
            if (iv.length != IV_BYTES) throw new TokenCipherException("Token could not be decrypted");
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(Base64.getDecoder().decode(value.ciphertext())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            // Wrong key, wrong tenant (AAD), tampered or malformed data all look the same to the caller.
            throw new TokenCipherException("Token could not be decrypted");
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new TokenCipherException("Token encryption is not configured");
    }

    private static byte[] aad(String associatedData) {
        if (associatedData == null || associatedData.isBlank()) {
            throw new TokenCipherException("Associated data is required");
        }
        return associatedData.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) throw new TokenCipherException("Token encryption key is required");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException invalid) {
            throw new TokenCipherException("Token encryption key must be Base64");
        }
        if (decoded.length != KEY_BYTES) {
            throw new TokenCipherException("Token encryption key must decode to exactly " + KEY_BYTES + " bytes");
        }
        return decoded;
    }

    /** Ciphertext, IV and key id, ready to store. {@code toString} is redacted so it is safe in logs. */
    public record EncryptedValue(String ciphertext, String iv, String keyId) {
        @Override
        public String toString() {
            return "EncryptedValue[keyId=" + keyId + ", redacted]";
        }
    }
}
