package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.config.CredentialsProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAesGcmCredentialEncryptorTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private LocalAesGcmCredentialEncryptor encryptor() {
        return new LocalAesGcmCredentialEncryptor(new CredentialsProperties("local-dev-only", VALID_KEY));
    }

    @Test
    void encryptThenDecrypt_roundTrips() {
        LocalAesGcmCredentialEncryptor encryptor = encryptor();
        EncryptedCredential encrypted = encryptor.encrypt("sk-ant-super-secret-token");

        assertThat(encryptor.decrypt(encrypted)).isEqualTo("sk-ant-super-secret-token");
    }

    @Test
    void encrypt_neverStoresPlaintextInCiphertext() {
        LocalAesGcmCredentialEncryptor encryptor = encryptor();
        EncryptedCredential encrypted = encryptor.encrypt("sk-ant-super-secret-token");

        assertThat(encrypted.ciphertext()).doesNotContain("sk-ant-super-secret-token");
    }

    @Test
    void encrypt_isNonDeterministic_dueToRandomIv() {
        LocalAesGcmCredentialEncryptor encryptor = encryptor();
        EncryptedCredential first = encryptor.encrypt("same-plaintext");
        EncryptedCredential second = encryptor.encrypt("same-plaintext");

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void encrypt_tagsWithKeyId() {
        LocalAesGcmCredentialEncryptor encryptor = encryptor();
        assertThat(encryptor.encrypt("token").keyId()).isEqualTo("local-v1");
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        LocalAesGcmCredentialEncryptor encryptor = encryptor();
        EncryptedCredential encrypted = encryptor.encrypt("sk-ant-super-secret-token");

        byte[] raw = Base64.getDecoder().decode(encrypted.ciphertext());
        raw[raw.length - 1] ^= 0x01; // flip a bit in the GCM tag
        EncryptedCredential tampered = new EncryptedCredential(Base64.getEncoder().encodeToString(raw), encrypted.keyId());

        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_unknownKeyId_throws() {
        LocalAesGcmCredentialEncryptor encryptor = encryptor();
        EncryptedCredential encrypted = encryptor.encrypt("token");
        EncryptedCredential wrongKeyId = new EncryptedCredential(encrypted.ciphertext(), "local-v2");

        assertThatThrownBy(() -> encryptor.decrypt(wrongKeyId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsWrongLengthKey() {
        CredentialsProperties badProps = new CredentialsProperties("local-dev-only",
                Base64.getEncoder().encodeToString(new byte[16])); // AES-128, not 256
        assertThatThrownBy(() -> new LocalAesGcmCredentialEncryptor(badProps))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsThePlaceholderValue_failsFastAtStartup() {
        // The exact default application.yml falls back to when
        // CREDENTIAL_LOCAL_KEY is unset -- must never silently work, or
        // every tenant's credentials end up encrypted with a key sitting
        // in plaintext in the source tree.
        CredentialsProperties placeholderProps = new CredentialsProperties("local-dev-only",
                "REPLACE_ME_WITH_A_STRONG_KEY_FROM_VAULT");

        assertThatThrownBy(() -> new LocalAesGcmCredentialEncryptor(placeholderProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder")
                .hasMessageContaining("CREDENTIAL_LOCAL_KEY");
    }

    @Test
    void constructor_rejectsInvalidBase64_withClearMessage() {
        CredentialsProperties badProps = new CredentialsProperties("local-dev-only", "not-valid-base64!!!");

        assertThatThrownBy(() -> new LocalAesGcmCredentialEncryptor(badProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
    }

    @Test
    void constructor_rejectsBlankKey() {
        CredentialsProperties blankProps = new CredentialsProperties("local-dev-only", "");

        assertThatThrownBy(() -> new LocalAesGcmCredentialEncryptor(blankProps))
                .isInstanceOf(IllegalStateException.class);
    }
}
