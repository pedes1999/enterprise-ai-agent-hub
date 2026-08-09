package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * localKey is a base64-encoded 256-bit AES key used ONLY by
 * LocalAesGcmCredentialEncryptor (provider = "local-dev-only"). It is not
 * itself a KMS -- swapping to real AWS KMS/Vault means adding a new
 * CredentialEncryptor implementation and switching which one is wired up,
 * not changing this class.
 */
@ConfigurationProperties(prefix = "app.credentials")
public record CredentialsProperties(String encryptionKeyProvider, String localKey) {
}
