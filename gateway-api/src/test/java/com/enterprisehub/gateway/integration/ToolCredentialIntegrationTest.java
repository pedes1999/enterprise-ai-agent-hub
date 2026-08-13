package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP coverage for /tool-credentials, same pattern as
 * RolesAndCredentialsIntegrationTest's vendor-credentials coverage --
 * proves storage, rotation, and cross-tenant isolation for real against
 * agent_hub_test (RLS included), not mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ToolCredentialIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerAdmin(String slug) {
        RegisterRequest request = new RegisterRequest(slug, slug, "admin@" + slug + ".com", "p@ssword123");
        return restTemplate.postForEntity(baseUrl() + "/auth/register", request, AuthResponse.class)
                .getBody().token();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void put_thenList_showsNoValueField() {
        String token = registerAdmin(uniqueSlug("git-cred"));

        HttpEntity<CreateToolCredentialRequest> putRequest = new HttpEntity<>(
                new CreateToolCredentialRequest("git", "ghp_realtoken"), authHeaders(token));
        ResponseEntity<String> putResponse = restTemplate.exchange(
                baseUrl() + "/tool-credentials", HttpMethod.PUT, putRequest, String.class);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putResponse.getBody()).doesNotContain("ghp_realtoken");

        ResponseEntity<ToolCredentialSummary[]> listResponse = restTemplate.exchange(
                baseUrl() + "/tool-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), ToolCredentialSummary[].class);
        assertThat(listResponse.getBody()).hasSize(1);
        assertThat(listResponse.getBody()[0].credentialKind()).isEqualTo("GIT");
    }

    @Test
    void put_twiceForSameKind_rotatesNotDuplicates() {
        String token = registerAdmin(uniqueSlug("git-cred"));

        for (String value : new String[]{"ghp_first", "ghp_second"}) {
            HttpEntity<CreateToolCredentialRequest> putRequest = new HttpEntity<>(
                    new CreateToolCredentialRequest("GIT", value), authHeaders(token));
            restTemplate.exchange(baseUrl() + "/tool-credentials", HttpMethod.PUT, putRequest, ToolCredentialSummary.class);
        }

        ResponseEntity<ToolCredentialSummary[]> listResponse = restTemplate.exchange(
                baseUrl() + "/tool-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), ToolCredentialSummary[].class);

        assertThat(listResponse.getBody()).hasSize(1);
    }

    @Test
    void delete_thenList_isEmpty() {
        String token = registerAdmin(uniqueSlug("git-cred"));
        restTemplate.exchange(baseUrl() + "/tool-credentials", HttpMethod.PUT,
                new HttpEntity<>(new CreateToolCredentialRequest("GIT", "ghp_token"), authHeaders(token)),
                ToolCredentialSummary.class);

        restTemplate.exchange(baseUrl() + "/tool-credentials/GIT", HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), Void.class);

        ResponseEntity<ToolCredentialSummary[]> listResponse = restTemplate.exchange(
                baseUrl() + "/tool-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), ToolCredentialSummary[].class);
        assertThat(listResponse.getBody()).isEmpty();
    }

    @Test
    void crossTenantIsolation_cannotSeeAnotherTenantsCredential() {
        String tenantAToken = registerAdmin(uniqueSlug("git-cred-a"));
        String tenantBToken = registerAdmin(uniqueSlug("git-cred-b"));

        restTemplate.exchange(baseUrl() + "/tool-credentials", HttpMethod.PUT,
                new HttpEntity<>(new CreateToolCredentialRequest("GIT", "ghp_tenant_a_secret"), authHeaders(tenantAToken)),
                ToolCredentialSummary.class);

        ResponseEntity<ToolCredentialSummary[]> tenantBList = restTemplate.exchange(
                baseUrl() + "/tool-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(tenantBToken)), ToolCredentialSummary[].class);

        assertThat(tenantBList.getBody()).isEmpty();
    }

    @Test
    void developer_forbiddenFromManagingToolCredentials() {
        String slug = uniqueSlug("git-cred-rbac");
        String adminToken = registerAdmin(slug);
        restTemplate.postForEntity(baseUrl() + "/users",
                new HttpEntity<>(new CreateUserRequest("dev@" + slug + ".com", "password123", "DEVELOPER"), authHeaders(adminToken)),
                UserSummary.class);
        String devToken = restTemplate.postForEntity(baseUrl() + "/auth/login",
                new LoginRequest(slug, "dev@" + slug + ".com", "password123"), AuthResponse.class).getBody().token();

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/tool-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(devToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
