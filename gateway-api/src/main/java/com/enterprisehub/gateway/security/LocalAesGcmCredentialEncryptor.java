package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.config.CredentialsProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope-encryption stand-in for local dev / self-hosted deployments that
 * don't have AWS KMS or Vault provisioned. Ciphertext layout is
 * base64(12-byte IV || AES-GCM ciphertext+tag) -- GCM's tag gives
 * authenticated encryption, so tampering with stored ciphertext fails
 * decryption loudly instead of silently returning garbage plaintext.
 *
 * keyId is fixed at "local-v1" because there is exactly one key today; if
 * this key ever needs rotating, a "local-v2" variant would need to keep
 * this class able to decrypt both, keyed off EncryptedCredential.keyId.
 */
@Component
public class LocalAesGcmCredentialEncryptor implements CredentialEncryptor {

    private static final String KEY_ID = "local-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    /**
     * Must match application.yml's default exactly. Rejected here, not just
     * left to fail base64 decoding on its own -- this string WOULD
     * base64-decode "successfully" to garbage bytes of the wrong length
     * (or, worse, some other length entirely by coincidence), so relying on
     * the length check alone to catch it is not guaranteed. Checking for
     * the literal placeholder up front makes "you forgot to set
     * CREDENTIAL_LOCAL_KEY" an unambiguous error message instead of a
     * confusing base64/length one.
     */
    static final String PLACEHOLDER_KEY = "REPLACE_ME_WITH_A_STRONG_KEY_FROM_VAULT";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Fails fast (at bean construction, i.e. application startup) rather
     * than on the first encrypt/decrypt call -- same fail-closed discipline
     * as TenantAwareDataSource always setting the RLS session variable. A
     * misconfigured key here means every tenant's vendor/tool credentials
     * would otherwise be encrypted with either no real secret at all or a
     * key known from the source tree -- this must never reach a running
     * deployment silently.
     */
    public LocalAesGcmCredentialEncryptor(CredentialsProperties properties) {
        String localKey = properties.localKey();
        if (PLACEHOLDER_KEY.equals(localKey)) {
            throw new IllegalStateException(
                    "app.credentials.local-key is still the placeholder value -- set CREDENTIAL_LOCAL_KEY to a real "
                            + "base64-encoded 256-bit key before starting this application. Generate one with: "
                            + "python3 -c \"import os,base64;print(base64.b64encode(os.urandom(32)).decode())\"");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(localKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.credentials.local-key is not valid base64 -- set CREDENTIAL_LOCAL_KEY to a real "
                            + "base64-encoded 256-bit key.", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "app.credentials.local-key must decode to exactly 32 bytes (AES-256), got " + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public EncryptedCredential encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);

            return new EncryptedCredential(Base64.getEncoder().encodeToString(buffer.array()), KEY_ID);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    @Override
    public String decrypt(EncryptedCredential encrypted) {
        if (!KEY_ID.equals(encrypted.keyId())) {
            throw new IllegalStateException("Unknown encryption key id: " + encrypted.keyId());
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encrypted.ciphertext());
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential -- ciphertext may be corrupt or tampered", e);
        }
    }
}
