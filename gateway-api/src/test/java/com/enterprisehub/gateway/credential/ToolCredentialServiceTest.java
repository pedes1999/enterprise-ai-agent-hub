package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateToolCredentialRequest;
import com.enterprisehub.dto.ToolCredentialSummary;
import com.enterprisehub.gateway.entity.ToolCredential;
import com.enterprisehub.gateway.repository.ToolCredentialRepository;
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

class ToolCredentialServiceTest {

    private ToolCredentialRepository repository;
    private CredentialEncryptor encryptor;
    private ToolCredentialService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ToolCredentialRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        service = new ToolCredentialService(repository, encryptor);
    }

    @Test
    void put_newCredential_encryptsAndSaves() {
        when(repository.findByTenantIdAndCredentialKind(tenantId, "GIT")).thenReturn(Optional.empty());
        when(encryptor.encrypt("ghp_secret")).thenReturn(new EncryptedCredential("ciphertext", "local-v1"));
        when(repository.save(any(ToolCredential.class))).thenAnswer(inv -> {
            ToolCredential c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        ToolCredentialSummary summary = service.put(tenantId, new CreateToolCredentialRequest("git", "ghp_secret"));

        assertThat(summary.credentialKind()).isEqualTo("GIT");
        assertThat(summary.active()).isTrue();
        verify(repository).save(argThat(c ->
                "ciphertext".equals(c.getEncryptedValue()) && "local-v1".equals(c.getEncryptionKeyId())));
    }

    @Test
    void put_neverPersistsPlaintextValue() {
        when(repository.findByTenantIdAndCredentialKind(any(), any())).thenReturn(Optional.empty());
        when(encryptor.encrypt("ghp_secret")).thenReturn(new EncryptedCredential("ciphertext", "local-v1"));
        when(repository.save(any())).thenAnswer(inv -> {
            ToolCredential c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        service.put(tenantId, new CreateToolCredentialRequest("GIT", "ghp_secret"));

        verify(repository).save(argThat(c -> !"ghp_secret".equals(c.getEncryptedValue())));
    }

    @Test
    void put_existingKind_rotatesInPlace_notDuplicateRow() {
        ToolCredential existing = new ToolCredential();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setCredentialKind("GIT");
        existing.setEncryptedValue("old-ciphertext");
        existing.setEncryptionKeyId("local-v1");

        when(repository.findByTenantIdAndCredentialKind(tenantId, "GIT")).thenReturn(Optional.of(existing));
        when(encryptor.encrypt("new-token")).thenReturn(new EncryptedCredential("new-ciphertext", "local-v1"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.put(tenantId, new CreateToolCredentialRequest("GIT", "new-token"));

        verify(repository).save(argThat(c -> existing.getId().equals(c.getId()) && "new-ciphertext".equals(c.getEncryptedValue())));
    }

    @Test
    void put_invalidKind_throwsBadRequest() {
        assertThatThrownBy(() -> service.put(tenantId, new CreateToolCredentialRequest("SSH", "token")))
                .isInstanceOf(ToolCredentialException.class)
                .satisfies(e -> assertThat(((ToolCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(encryptor);
    }

    @Test
    void put_blankValue_throwsBadRequest() {
        assertThatThrownBy(() -> service.put(tenantId, new CreateToolCredentialRequest("GIT", " ")))
                .isInstanceOf(ToolCredentialException.class);
        verifyNoInteractions(encryptor);
    }

    @Test
    void list_returnsSummariesWithoutValues() {
        ToolCredential credential = new ToolCredential();
        credential.setId(UUID.randomUUID());
        credential.setCredentialKind("GIT");
        credential.setEncryptedValue("ciphertext");
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(credential));

        List<ToolCredentialSummary> summaries = service.list(tenantId);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).credentialKind()).isEqualTo("GIT");
    }

    @Test
    void delete_existingKind_removesRow() {
        ToolCredential credential = new ToolCredential();
        credential.setCredentialKind("GIT");
        when(repository.findByTenantIdAndCredentialKind(tenantId, "GIT")).thenReturn(Optional.of(credential));

        service.delete(tenantId, "git");

        verify(repository).delete(credential);
    }

    @Test
    void delete_noStoredCredential_throwsNotFound() {
        when(repository.findByTenantIdAndCredentialKind(tenantId, "GIT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(tenantId, "GIT"))
                .isInstanceOf(ToolCredentialException.class)
                .satisfies(e -> assertThat(((ToolCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void delete_invalidKind_throwsBadRequest() {
        assertThatThrownBy(() -> service.delete(tenantId, "SSH"))
                .isInstanceOf(ToolCredentialException.class)
                .satisfies(e -> assertThat(((ToolCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void decryptActiveValue_activeCredential_returnsDecryptedValue() {
        ToolCredential credential = new ToolCredential();
        credential.setEncryptedValue("ciphertext");
        credential.setEncryptionKeyId("local-v1");
        credential.setActive(true);
        when(repository.findByTenantIdAndCredentialKind(tenantId, "GIT")).thenReturn(Optional.of(credential));
        when(encryptor.decrypt(new EncryptedCredential("ciphertext", "local-v1"))).thenReturn("plaintext-token");

        assertThat(service.decryptActiveValue(tenantId, "GIT")).contains("plaintext-token");
    }

    @Test
    void decryptActiveValue_inactiveCredential_returnsEmpty() {
        ToolCredential credential = new ToolCredential();
        credential.setActive(false);
        when(repository.findByTenantIdAndCredentialKind(tenantId, "GIT")).thenReturn(Optional.of(credential));

        assertThat(service.decryptActiveValue(tenantId, "GIT")).isEmpty();
        verifyNoInteractions(encryptor);
    }

    @Test
    void decryptActiveValue_noCredentialConfigured_returnsEmpty_notAnError() {
        when(repository.findByTenantIdAndCredentialKind(tenantId, "GIT")).thenReturn(Optional.empty());

        assertThat(service.decryptActiveValue(tenantId, "GIT")).isEmpty();
    }
}
