package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CredentialTestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ToolCredentialTestServiceTest {

    private ToolCredentialService toolCredentialService;
    private MockRestServiceServer mockServer;
    private ToolCredentialTestService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        toolCredentialService = mock(ToolCredentialService.class);
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service = new ToolCredentialTestService(toolCredentialService, builder);
    }

    @Test
    void test_gitKind_notSupported_neverCallsGithub() {
        CredentialTestResult result = service.test(tenantId, "GIT");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("not supported");
        mockServer.verify(); // no expectations set -- verifies no HTTP call was made
        verifyNoInteractions(toolCredentialService);
    }

    @Test
    void test_invalidKind_throwsBadRequest() {
        assertThatThrownBy(() -> service.test(tenantId, "SSH"))
                .isInstanceOf(ToolCredentialException.class)
                .satisfies(e -> assertThat(((ToolCredentialException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void test_githubKind_noCredentialStored_throwsNotFound() {
        when(toolCredentialService.decryptActiveValue(tenantId, "GITHUB")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.test(tenantId, "GITHUB"))
                .isInstanceOf(ToolCredentialException.class)
                .satisfies(e -> assertThat(((ToolCredentialException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void test_githubKind_validToken_returnsValid_marksValidated() {
        when(toolCredentialService.decryptActiveValue(tenantId, "GITHUB")).thenReturn(Optional.of("ghp_realtoken"));
        mockServer.expect(requestTo("https://api.github.com/user"))
                .andExpect(header("Authorization", "Bearer ghp_realtoken"))
                .andRespond(withSuccess("{\"login\":\"someuser\"}", MediaType.APPLICATION_JSON));

        CredentialTestResult result = service.test(tenantId, "GITHUB");

        assertThat(result.valid()).isTrue();
        mockServer.verify();
        verify(toolCredentialService).markValidated(tenantId, "GITHUB");
    }

    @Test
    void test_githubKind_revokedToken_returnsInvalid_neverMarksValidated() {
        when(toolCredentialService.decryptActiveValue(tenantId, "GITHUB")).thenReturn(Optional.of("ghp_revoked"));
        mockServer.expect(requestTo("https://api.github.com/user"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        CredentialTestResult result = service.test(tenantId, "GITHUB");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("401");
        verify(toolCredentialService, never()).markValidated(any(), any());
    }
}
