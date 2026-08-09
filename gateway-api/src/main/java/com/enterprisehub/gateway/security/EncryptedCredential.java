package com.enterprisehub.gateway.security;

/**
 * ciphertext is base64. keyId identifies WHICH key/version encrypted it --
 * stored alongside the ciphertext in VendorCredential.encryptionKeyId so a
 * future key rotation can tell old rows (decrypt with key v1) apart from
 * new ones (key v2), without needing to re-encrypt everything atomically.
 */
public record EncryptedCredential(String ciphertext, String keyId) {
}
