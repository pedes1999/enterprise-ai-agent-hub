package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateVendorCredentialRequest;
import com.enterprisehub.dto.VendorCredentialSummary;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.enterprisehub.gateway.security.CredentialEncryptor;
import com.enterprisehub.gateway.security.EncryptedCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VendorCredentialServiceTest {

    private VendorCredentialRepository repository;
    private CredentialEncryptor encryptor;
    private VendorCredentialService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(VendorCredentialRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        service = new VendorCredentialService(repository, encryptor);
    }

    @Test
    void put_newCredential_encryptsAndSaves() {
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());
        when(encryptor.encrypt("sk-ant-secret")).thenReturn(new EncryptedCredential("ciphertext", "local-v1"));
        when(repository.save(any(VendorCredential.class))).thenAnswer(inv -> {
            VendorCredential c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        VendorCredentialSummary summary = service.put(tenantId, new CreateVendorCredentialRequest("anthropic", "sk-ant-secret"));

        assertThat(summary.provider()).isEqualTo("ANTHROPIC");
        assertThat(summary.active()).isTrue();
        verify(repository).save(argThat(c ->
                "ciphertext".equals(c.getEncryptedToken()) && "local-v1".equals(c.getEncryptionKeyId())));
    }

    @Test
    void put_neverPersistsPlaintextToken() {
        when(repository.findByTenantIdAndProvider(any(), any())).thenReturn(Optional.empty());
        when(encryptor.encrypt("sk-ant-secret")).thenReturn(new EncryptedCredential("ciphertext", "local-v1"));
        when(repository.save(any())).thenAnswer(inv -> {
            VendorCredential c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        service.put(tenantId, new CreateVendorCredentialRequest("ANTHROPIC", "sk-ant-secret"));

        verify(repository).save(argThat(c -> !"sk-ant-secret".equals(c.getEncryptedToken())));
    }

    @Test
    void put_existingProvider_rotatesInPlace_notDuplicateRow() {
        VendorCredential existing = new VendorCredential();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setProvider("ANTHROPIC");
        existing.setEncryptedToken("old-ciphertext");
        existing.setEncryptionKeyId("local-v1");

        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(existing));
        when(encryptor.encrypt("new-token")).thenReturn(new EncryptedCredential("new-ciphertext", "local-v1"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.put(tenantId, new CreateVendorCredentialRequest("ANTHROPIC", "new-token"));

        verify(repository).save(argThat(c -> existing.getId().equals(c.getId()) && "new-ciphertext".equals(c.getEncryptedToken())));
    }

    @Test
    void put_invalidProvider_throwsBadRequest() {
        assertThatThrownBy(() -> service.put(tenantId, new CreateVendorCredentialRequest("COHERE", "token")))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(encryptor);
    }

    @Test
    void put_blankToken_throwsBadRequest() {
        assertThatThrownBy(() -> service.put(tenantId, new CreateVendorCredentialRequest("ANTHROPIC", " ")))
                .isInstanceOf(VendorCredentialException.class);
        verifyNoInteractions(encryptor);
    }

    @Test
    void list_returnsSummariesWithoutTokens() {
        VendorCredential credential = new VendorCredential();
        credential.setId(UUID.randomUUID());
        credential.setProvider("OPENAI");
        credential.setEncryptedToken("ciphertext");
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(credential));

        List<VendorCredentialSummary> summaries = service.list(tenantId);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).provider()).isEqualTo("OPENAI");
    }

    @Test
    void delete_existingProvider_removesRow() {
        VendorCredential credential = new VendorCredential();
        credential.setProvider("GEMINI");
        when(repository.findByTenantIdAndProvider(tenantId, "GEMINI")).thenReturn(Optional.of(credential));

        service.delete(tenantId, "gemini");

        verify(repository).delete(credential);
    }

    @Test
    void delete_noStoredCredential_throwsNotFound() {
        when(repository.findByTenantIdAndProvider(tenantId, "GEMINI")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(tenantId, "GEMINI"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void delete_invalidProvider_throwsBadRequest() {
        assertThatThrownBy(() -> service.delete(tenantId, "COHERE"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void decryptToken_delegatesToEncryptor() {
        VendorCredential credential = new VendorCredential();
        credential.setEncryptedToken("ciphertext");
        credential.setEncryptionKeyId("local-v1");
        when(encryptor.decrypt(new EncryptedCredential("ciphertext", "local-v1"))).thenReturn("plaintext-token");

        assertThat(service.decryptToken(credential)).isEqualTo("plaintext-token");
    }

    @Test
    void decryptToken_stampsLastUsedAt() {
        VendorCredential credential = new VendorCredential();
        credential.setEncryptedToken("ciphertext");
        credential.setEncryptionKeyId("local-v1");
        when(encryptor.decrypt(any())).thenReturn("plaintext-token");

        service.decryptToken(credential);

        assertThat(credential.getLastUsedAt()).isNotNull();
        verify(repository).save(credential);
    }

    @Test
    void markValidated_existingCredential_stampsLastValidatedAt() {
        VendorCredential credential = new VendorCredential();
        credential.setProvider("ANTHROPIC");
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.of(credential));

        service.markValidated(tenantId, "anthropic");

        assertThat(credential.getLastValidatedAt()).isNotNull();
        verify(repository).save(credential);
    }

    @Test
    void markValidated_noStoredCredential_doesNothing_doesNotThrow() {
        when(repository.findByTenantIdAndProvider(tenantId, "ANTHROPIC")).thenReturn(Optional.empty());

        service.markValidated(tenantId, "ANTHROPIC");

        verify(repository, never()).save(any());
    }

    @Test
    void markValidated_invalidProvider_doesNothing_doesNotThrow() {
        service.markValidated(tenantId, "COHERE");

        verifyNoInteractions(repository);
    }
}
