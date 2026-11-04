package com.waypoint.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {

    private static final String REFRESH_TOKEN = "1//refresh-token-value-for-tests";

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private final String keyText = newKey();
    private final TokenCipher cipher = new TokenCipher(keyText, "v1");

    @Test
    void encryptsAndDecryptsForTheSameOrganization() {
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(REFRESH_TOKEN, "org-1");

        assertThat(encrypted.keyId()).isEqualTo("v1");
        assertThat(encrypted.ciphertext()).doesNotContain(REFRESH_TOKEN);
        assertThat(cipher.decrypt(encrypted, "org-1")).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    void everyEncryptionUsesAFreshIvSoEqualPlaintextsDiffer() {
        TokenCipher.EncryptedValue first = cipher.encrypt(REFRESH_TOKEN, "org-1");
        TokenCipher.EncryptedValue second = cipher.encrypt(REFRESH_TOKEN, "org-1");

        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(Base64.getDecoder().decode(first.iv())).hasSize(12);
    }

    @Test
    void aCiphertextCopiedToAnotherOrganizationCannotBeDecrypted() {
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(REFRESH_TOKEN, "org-1");

        assertThatThrownBy(() -> cipher.decrypt(encrypted, "org-2"))
                .isInstanceOf(TokenCipherException.class)
                .hasMessage("Token could not be decrypted");
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(REFRESH_TOKEN, "org-1");
        TokenCipher other = new TokenCipher(newKey(), "v1");

        assertThatThrownBy(() -> other.decrypt(encrypted, "org-1")).isInstanceOf(TokenCipherException.class);
    }

    @Test
    void aValueEncryptedUnderAnotherKeyIdIsRejectedBeforeDecryption() {
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(REFRESH_TOKEN, "org-1");
        TokenCipher rotated = new TokenCipher(keyText, "v2");

        assertThatThrownBy(() -> rotated.decrypt(encrypted, "org-1"))
                .isInstanceOf(TokenCipherException.class)
                .hasMessageContaining("unknown key id");
    }

    @Test
    void tamperedCiphertextIvOrMalformedValuesAreRejected() {
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(REFRESH_TOKEN, "org-1");
        byte[] bytes = Base64.getDecoder().decode(encrypted.ciphertext());
        bytes[0] ^= 1;
        TokenCipher.EncryptedValue tampered = new TokenCipher.EncryptedValue(
                Base64.getEncoder().encodeToString(bytes), encrypted.iv(), "v1");
        TokenCipher.EncryptedValue wrongIv = new TokenCipher.EncryptedValue(
                encrypted.ciphertext(), Base64.getEncoder().encodeToString(new byte[12]), "v1");
        TokenCipher.EncryptedValue shortIv = new TokenCipher.EncryptedValue(
                encrypted.ciphertext(), Base64.getEncoder().encodeToString(new byte[4]), "v1");
        TokenCipher.EncryptedValue garbage = new TokenCipher.EncryptedValue("not base64!", encrypted.iv(), "v1");

        for (TokenCipher.EncryptedValue bad : new TokenCipher.EncryptedValue[]{tampered, wrongIv, shortIv, garbage}) {
            assertThatThrownBy(() -> cipher.decrypt(bad, "org-1")).isInstanceOf(TokenCipherException.class);
        }
        assertThatThrownBy(() -> cipher.decrypt(null, "org-1")).isInstanceOf(TokenCipherException.class);
    }

    @Test
    void associatedDataAndPlaintextAreRequired() {
        assertThatThrownBy(() -> cipher.encrypt(REFRESH_TOKEN, null)).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> cipher.encrypt(REFRESH_TOKEN, " ")).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> cipher.encrypt("", "org-1")).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> cipher.encrypt(null, "org-1")).isInstanceOf(TokenCipherException.class);
    }

    @Test
    void theKeyMustBeBase64OfExactly32Bytes() {
        assertThatThrownBy(() -> new TokenCipher(null, "v1")).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> new TokenCipher(" ", "v1")).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> new TokenCipher("%%%not-base64%%%", "v1")).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16]), "v1"))
                .isInstanceOf(TokenCipherException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[33]), "v1"))
                .isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> new TokenCipher(keyText, " ")).isInstanceOf(TokenCipherException.class);
    }

    @Test
    void anUnconfiguredCipherRefusesEveryOperation() {
        TokenCipher none = TokenCipher.unconfigured();

        assertThat(none.isConfigured()).isFalse();
        assertThatThrownBy(() -> none.encrypt(REFRESH_TOKEN, "org-1")).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> none.decrypt(cipher.encrypt(REFRESH_TOKEN, "org-1"), "org-1"))
                .isInstanceOf(TokenCipherException.class);
    }

    @Test
    void failureMessagesAndToStringNeverContainKeyPlaintextOrCiphertext() {
        TokenCipher.EncryptedValue encrypted = cipher.encrypt(REFRESH_TOKEN, "org-1");

        assertThat(encrypted.toString()).doesNotContain(encrypted.ciphertext()).doesNotContain(encrypted.iv())
                .doesNotContain(REFRESH_TOKEN);
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> cipher.decrypt(encrypted, "org-2"));
        assertThat(failure.getMessage()).doesNotContain(keyText).doesNotContain(REFRESH_TOKEN)
                .doesNotContain(encrypted.ciphertext());
        Throwable badKey = org.assertj.core.api.Assertions.catchThrowable(
                () -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[5]), "v1"));
        assertThat(badKey.getMessage()).doesNotContain(Base64.getEncoder().encodeToString(new byte[5]));
    }
}
