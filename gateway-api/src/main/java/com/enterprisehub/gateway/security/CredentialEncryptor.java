package com.enterprisehub.gateway.security;

/**
 * Abstraction over "however vendor API tokens get encrypted at rest."
 * VendorCredentialService depends on this interface only -- swapping the
 * local AES-GCM implementation for real AWS KMS or HashiCorp Vault later is
 * a new implementation of this interface plus a Spring @Primary/@Profile
 * switch, not a change to any calling code.
 */
public interface CredentialEncryptor {

    EncryptedCredential encrypt(String plaintext);

    String decrypt(EncryptedCredential encrypted);
}
