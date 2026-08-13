package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CreateVendorCredentialRequest;
import com.enterprisehub.dto.TeamVendorCredentialSummary;
import com.enterprisehub.dto.VendorCredentialSummary;
import com.enterprisehub.gateway.entity.AppUser;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.AppUserRepository;
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
    private AppUserRepository appUserRepository;
    private CredentialEncryptor encryptor;
    private VendorCredentialService service;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(VendorCredentialRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        service = new VendorCredentialService(repository, appUserRepository, encryptor);
    }

    @Test
    void put_newCredential_encryptsAndSaves() {
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.empty());
        when(encryptor.encrypt("sk-ant-secret")).thenReturn(new EncryptedCredential("ciphertext", "local-v1"));
        when(repository.save(any(VendorCredential.class))).thenAnswer(inv -> {
            VendorCredential c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        VendorCredentialSummary summary = service.put(tenantId, userId, new CreateVendorCredentialRequest("anthropic", "sk-ant-secret"));

        assertThat(summary.provider()).isEqualTo("ANTHROPIC");
        assertThat(summary.active()).isTrue();
        verify(repository).save(argThat(c ->
                "ciphertext".equals(c.getEncryptedToken()) && "local-v1".equals(c.getEncryptionKeyId()) && userId.equals(c.getUserId())));
    }

    @Test
    void put_neverPersistsPlaintextToken() {
        when(repository.findByTenantIdAndUserIdAndProvider(any(), any(), any())).thenReturn(Optional.empty());
        when(encryptor.encrypt("sk-ant-secret")).thenReturn(new EncryptedCredential("ciphertext", "local-v1"));
        when(repository.save(any())).thenAnswer(inv -> {
            VendorCredential c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        service.put(tenantId, userId, new CreateVendorCredentialRequest("ANTHROPIC", "sk-ant-secret"));

        verify(repository).save(argThat(c -> !"sk-ant-secret".equals(c.getEncryptedToken())));
    }

    @Test
    void put_existingProvider_rotatesInPlace_notDuplicateRow() {
        VendorCredential existing = new VendorCredential();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setUserId(userId);
        existing.setProvider("ANTHROPIC");
        existing.setEncryptedToken("old-ciphertext");
        existing.setEncryptionKeyId("local-v1");

        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.of(existing));
        when(encryptor.encrypt("new-token")).thenReturn(new EncryptedCredential("new-ciphertext", "local-v1"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.put(tenantId, userId, new CreateVendorCredentialRequest("ANTHROPIC", "new-token"));

        verify(repository).save(argThat(c -> existing.getId().equals(c.getId()) && "new-ciphertext".equals(c.getEncryptedToken())));
    }

    @Test
    void put_invalidProvider_throwsBadRequest() {
        assertThatThrownBy(() -> service.put(tenantId, userId, new CreateVendorCredentialRequest("COHERE", "token")))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(encryptor);
    }

    @Test
    void put_blankToken_throwsBadRequest() {
        assertThatThrownBy(() -> service.put(tenantId, userId, new CreateVendorCredentialRequest("ANTHROPIC", " ")))
                .isInstanceOf(VendorCredentialException.class);
        verifyNoInteractions(encryptor);
    }

    @Test
    void list_returnsCallersOwnSummariesWithoutTokens() {
        VendorCredential credential = new VendorCredential();
        credential.setId(UUID.randomUUID());
        credential.setProvider("OPENAI");
        credential.setEncryptedToken("ciphertext");
        when(repository.findByTenantIdAndUserId(tenantId, userId)).thenReturn(List.of(credential));

        List<VendorCredentialSummary> summaries = service.list(tenantId, userId);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).provider()).isEqualTo("OPENAI");
    }

    @Test
    void delete_existingProvider_removesRow() {
        VendorCredential credential = new VendorCredential();
        credential.setProvider("GEMINI");
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "GEMINI")).thenReturn(Optional.of(credential));

        service.delete(tenantId, userId, "gemini");

        verify(repository).delete(credential);
    }

    @Test
    void delete_noStoredCredential_throwsNotFound() {
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "GEMINI")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(tenantId, userId, "GEMINI"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void delete_invalidProvider_throwsBadRequest() {
        assertThatThrownBy(() -> service.delete(tenantId, userId, "COHERE"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void delete_doesNotTouchAnotherUsersCredentialForTheSameProvider() {
        // Scoped by userId, not just tenantId+provider -- one developer's
        // delete must never be able to reach a teammate's row, unlike the
        // old tenant-wide model this replaces.
        UUID otherUserId = UUID.randomUUID();
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(tenantId, userId, "ANTHROPIC"))
                .isInstanceOf(VendorCredentialException.class);
        verify(repository, never()).findByTenantIdAndUserIdAndProvider(tenantId, otherUserId, "ANTHROPIC");
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
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.of(credential));

        service.markValidated(tenantId, userId, "anthropic");

        assertThat(credential.getLastValidatedAt()).isNotNull();
        verify(repository).save(credential);
    }

    @Test
    void markValidated_noStoredCredential_doesNothing_doesNotThrow() {
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.empty());

        service.markValidated(tenantId, userId, "ANTHROPIC");

        verify(repository, never()).save(any());
    }

    @Test
    void markValidated_invalidProvider_doesNothing_doesNotThrow() {
        service.markValidated(tenantId, userId, "COHERE");

        verifyNoInteractions(repository);
    }

    @Test
    void listForTeam_returnsEveryUsersCredentialsWithOwnerEmail_noSecrets() {
        UUID otherUserId = UUID.randomUUID();
        VendorCredential mine = new VendorCredential();
        mine.setUserId(userId);
        mine.setProvider("ANTHROPIC");
        mine.setActive(true);
        VendorCredential theirs = new VendorCredential();
        theirs.setUserId(otherUserId);
        theirs.setProvider("OPENAI");
        theirs.setActive(false);
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(mine, theirs));

        AppUser me = new AppUser();
        me.setId(userId);
        me.setEmail("me@acme.com");
        AppUser them = new AppUser();
        them.setId(otherUserId);
        them.setEmail("them@acme.com");
        when(appUserRepository.findByTenantId(tenantId)).thenReturn(List.of(me, them));

        List<TeamVendorCredentialSummary> team = service.listForTeam(tenantId);

        assertThat(team).hasSize(2);
        assertThat(team).anySatisfy(row -> {
            assertThat(row.userEmail()).isEqualTo("me@acme.com");
            assertThat(row.provider()).isEqualTo("ANTHROPIC");
            assertThat(row.active()).isTrue();
        });
        assertThat(team).anySatisfy(row -> {
            assertThat(row.userEmail()).isEqualTo("them@acme.com");
            assertThat(row.provider()).isEqualTo("OPENAI");
            assertThat(row.active()).isFalse();
        });
    }

    @Test
    void deactivateForUser_flipsActiveFalse_neverReadsTheEncryptedValue() {
        VendorCredential credential = new VendorCredential();
        credential.setUserId(userId);
        credential.setProvider("ANTHROPIC");
        credential.setActive(true);
        credential.setEncryptedToken("ciphertext-never-touched");
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.of(credential));

        service.deactivateForUser(tenantId, userId, "anthropic");

        assertThat(credential.isActive()).isFalse();
        assertThat(credential.getEncryptedToken()).isEqualTo("ciphertext-never-touched");
        verify(encryptor, never()).decrypt(any());
        verify(repository).save(credential);
    }

    @Test
    void deactivateForUser_noStoredCredential_throwsNotFound() {
        when(repository.findByTenantIdAndUserIdAndProvider(tenantId, userId, "ANTHROPIC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateForUser(tenantId, userId, "ANTHROPIC"))
                .isInstanceOf(VendorCredentialException.class)
                .satisfies(e -> assertThat(((VendorCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
